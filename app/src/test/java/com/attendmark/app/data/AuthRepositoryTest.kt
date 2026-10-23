package com.attendmark.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AuthRepository verifying authentication flow, input validation,
 * error mapping, and strict server-side role resolution rules.
 */
class AuthRepositoryTest {

    private lateinit var fakeAuthProvider: FakeAuthProvider
    private lateinit var fakeProfileStore: FakeUserProfileStore
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        fakeAuthProvider = FakeAuthProvider()
        fakeProfileStore = FakeUserProfileStore()
        repository = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore
        )
    }

    @Test
    fun login_withBlankEmail_returnsError() = runBlocking {
        val result = repository.login("   ", "password123")
        assertTrue(result is AuthResult.Error)
        assertEquals("Please enter email and password", (result as AuthResult.Error).message)
    }

    @Test
    fun login_withBlankPassword_returnsError() = runBlocking {
        val result = repository.login("student@test.com", "   ")
        assertTrue(result is AuthResult.Error)
        assertEquals("Please enter email and password", (result as AuthResult.Error).message)
    }

    @Test
    fun login_withWhitespace_trimsInputBeforeSubmitting() = runBlocking {
        fakeAuthProvider.configureUser("student@test.com", "password123", "uid-100", "Student User")
        fakeProfileStore.setProfile("uid-100", mapOf("role" to "student", "name" to "Student User"))

        val result = repository.login("  student@test.com  ", "  password123  ")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student@test.com", user.email)
        assertEquals("uid-100", user.uid)
        assertEquals("student", user.role)
    }

    @Test
    fun login_whenAuthFails_returnsMappedErrorMessage() = runBlocking {
        fakeAuthProvider.configureFailure("The email address is badly formatted.")

        val result = repository.login("bademail", "pass")
        assertTrue(result is AuthResult.Error)
        assertEquals("The email address is badly formatted.", (result as AuthResult.Error).message)
    }

    @Test
    fun login_whenNetworkFails_returnsCleanNetworkMessage() = runBlocking {
        fakeAuthProvider.configureFailure("A network error (such as timeout, interrupted connection or unreachable host) has occurred.")

        val result = repository.login("student@test.com", "password123")
        assertTrue(result is AuthResult.Error)
        assertEquals("Network error. Please check your internet connection.", (result as AuthResult.Error).message)
    }

    @Test
    fun login_whenFacultyProfileExists_resolvesFacultyRole() = runBlocking {
        fakeAuthProvider.configureUser("faculty@test.com", "faculty123", "uid-fac-1", "Prof. Smith")
        fakeProfileStore.setProfile("uid-fac-1", mapOf("role" to "faculty", "name" to "Prof. Smith"))

        val result = repository.login("faculty@test.com", "faculty123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("faculty", user.role)
        assertEquals("Prof. Smith", user.name)
        assertEquals("uid-fac-1", user.uid)
    }

    @Test
    fun login_whenStudentProfileExists_resolvesStudentRole() = runBlocking {
        fakeAuthProvider.configureUser("student@test.com", "student123", "uid-stu-1", "Alice Student")
        fakeProfileStore.setProfile("uid-stu-1", mapOf("role" to "student", "name" to "Alice Student"))

        val result = repository.login("student@test.com", "student123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("Alice Student", user.name)
    }

    @Test
    fun login_whenUserProfileMissing_createsStudentProfileAndResolvesStudent() = runBlocking {
        fakeAuthProvider.configureUser("newstudent@test.com", "student123", "uid-new", null)

        val result = repository.login("newstudent@test.com", "student123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("Newstudent", user.name)

        // Verify that the initial profile was created strictly as "student"
        val savedProfile = fakeProfileStore.getStoredProfile("uid-new")
        assertEquals("student", savedProfile?.get("role"))
        assertEquals("newstudent@test.com", savedProfile?.get("email"))
    }

    @Test
    fun login_whenUserProfileHasDisallowedRole_strictlyResolvesStudent() = runBlocking {
        fakeAuthProvider.configureUser("attacker@test.com", "pass123", "uid-attacker", "Attacker")
        // User attempted to store "admin" or other role
        fakeProfileStore.setProfile("uid-attacker", mapOf("role" to "admin", "name" to "Attacker"))

        val result = repository.login("attacker@test.com", "pass123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        // Must be downgraded to student; only explicit "faculty" role is permitted as elevated role
        assertEquals("student", user.role)
    }

    @Test
    fun logout_callsSignOutOnAuthProvider() {
        fakeAuthProvider.configureUser("user@test.com", "pass", "uid-1", "User")
        repository.logout()
        assertTrue(fakeAuthProvider.signOutCalled)
        assertNull(repository.getCurrentUser())
    }
}

/**
 * In-memory test double for AuthProvider.
 */
class FakeAuthProvider : AuthProvider {
    private val users = mutableMapOf<Pair<String, String>, Pair<String, String?>>()
    private var failureMessage: String? = null
    var signOutCalled: Boolean = false
    private var currentUser: Pair<String, String?>? = null

    fun configureUser(email: String, pass: String, uid: String, displayName: String?) {
        users[Pair(email, pass)] = Pair(uid, displayName)
    }

    fun configureFailure(message: String) {
        failureMessage = message
    }

    override suspend fun signIn(email: String, password: String): Result<Pair<String, String?>> {
        failureMessage?.let {
            return Result.failure(Exception(it))
        }
        val match = users[Pair(email, password)]
        return if (match != null) {
            currentUser = match
            Result.success(match)
        } else {
            Result.failure(Exception("Invalid email or password."))
        }
    }

    override fun signOut() {
        signOutCalled = true
        currentUser = null
    }

    override fun getCurrentUser(): Pair<String, String?>? = currentUser
}

/**
 * In-memory test double for UserProfileStore.
 */
class FakeUserProfileStore : UserProfileStore {
    private val profiles = mutableMapOf<String, Map<String, Any>>()
    var shouldFail: Boolean = false

    fun setProfile(uid: String, data: Map<String, Any>) {
        profiles[uid] = data
    }

    fun getStoredProfile(uid: String): Map<String, Any>? = profiles[uid]

    override suspend fun getUserProfile(uid: String): Result<Map<String, Any>?> {
        if (shouldFail) return Result.failure(Exception("Database connection error"))
        return Result.success(profiles[uid])
    }

    override suspend fun createInitialStudentProfile(
        uid: String,
        email: String,
        name: String
    ): Result<Unit> {
        if (shouldFail) return Result.failure(Exception("Failed to write profile"))
        profiles[uid] = mapOf(
            "email" to email,
            "name" to name,
            "role" to "student"
        )
        return Result.success(Unit)
    }
}
