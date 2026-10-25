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
        val result = repository.login("student@test.com", "")
        assertTrue(result is AuthResult.Error)
        assertEquals("Please enter email and password", (result as AuthResult.Error).message)
    }

    @Test
    fun login_trimsEmail_preservesExactPassword() = runBlocking {
        // Password with spaces must be preserved exactly
        fakeAuthProvider.configureUser("student@test.com", "  pass with spaces  ", "uid-100", "Student User")
        fakeProfileStore.setProfile("uid-100", mapOf("role" to "student", "name" to "Student User"))

        val result = repository.login("  student@test.com  ", "  pass with spaces  ")
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
        assertEquals("faculty@test.com", user.email)
        assertEquals("Prof. Smith", user.name)
        assertEquals("uid-fac-1", user.uid)
    }

    @Test
    fun login_whenStudentProfileExists_resolvesStudentRole() = runBlocking {
        fakeAuthProvider.configureUser("student@test.com", "student123", "uid-stu-1", "Alice")
        fakeProfileStore.setProfile("uid-stu-1", mapOf("role" to "student", "name" to "Alice"))

        val result = repository.login("student@test.com", "student123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("Alice", user.name)
    }

    @Test
    fun login_whenNoProfileExists_defaultsToStudentAndCreatesProfile() = runBlocking {
        fakeAuthProvider.configureUser("new@test.com", "pass123", "uid-new", "New Student")

        val result = repository.login("new@test.com", "pass123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role) // Safe default: cannot be faculty

        // Verifies profile was automatically created
        val created = fakeProfileStore.getProfile("uid-new")
        assertTrue(created != null)
        assertEquals("student", created?.get("role"))
    }

    @Test
    fun login_cannotSelfAssignFacultyRoleEvenIfDocumentAttemptsTampering() = runBlocking {
        fakeAuthProvider.configureUser("attacker@test.com", "pass123", "uid-att", null)
        // Store an arbitrary/tampered role like "admin" or "FACULTY_OWNER"
        fakeProfileStore.setProfile("uid-att", mapOf("role" to "superadmin", "name" to "Attacker"))

        val result = repository.login("attacker@test.com", "pass123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        // Must strictly fall back to "student"
        assertEquals("student", user.role)
    }

    @Test
    fun logout_callsAuthProviderSignOut() {
        fakeAuthProvider.configureUser("student@test.com", "pass", "uid-1", null)
        repository.logout()
        assertTrue(fakeAuthProvider.signOutCalled)
        assertNull(fakeAuthProvider.getCurrentUser())
    }

    @Test
    fun login_whenBackendReturnsAuthoritativeFacultyRole_assignsFacultyRole() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            responseMap = mapOf(
                "role" to "faculty",
                "name" to "Prof. Turing",
                "uid" to "uid-fac-render",
                "email" to "turing@univ.edu"
            )
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )
        fakeAuthProvider.configureUser("turing@univ.edu", "pass123", "uid-fac-render", null)

        val result = customRepo.login("turing@univ.edu", "pass123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("faculty", user.role)
        assertEquals("Prof. Turing", user.name)
        assertEquals("bootstrap-profile", fakeBackend.lastEndpoint)
    }

    @Test
    fun login_whenBackendReturnsAuthoritativeStudentRole_assignsStudentRole() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            responseMap = mapOf(
                "role" to "student",
                "name" to "Student 21BCS",
                "uid" to "uid-stu-render",
                "email" to "21bcs001@univ.edu"
            )
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )
        fakeAuthProvider.configureUser("21bcs001@univ.edu", "pass123", "uid-stu-render", null)

        val result = customRepo.login("21bcs001@univ.edu", "pass123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("Student 21BCS", user.name)
    }

    @Test
    fun login_whenBackendFails_fallsBackToFirestoreProfileStore() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            shouldFail = true
            failureCode = "UNAVAILABLE"
            failureMessage = "Cold start timeout"
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )
        fakeAuthProvider.configureUser("student@test.com", "pass123", "uid-offline", "Offline Student")
        fakeProfileStore.setProfile("uid-offline", mapOf("role" to "student", "name" to "Offline Student"))

        val result = customRepo.login("student@test.com", "pass123")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("Offline Student", user.name)
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
    var lastCreatedProfile: Pair<String, Map<String, Any>>? = null

    fun setProfile(uid: String, data: Map<String, Any>) {
        profiles[uid] = data
    }

    fun getProfile(uid: String): Map<String, Any>? = profiles[uid]

    override suspend fun getUserProfile(uid: String): Result<Map<String, Any>?> {
        return Result.success(profiles[uid])
    }

    override suspend fun createInitialStudentProfile(uid: String, email: String, name: String): Result<Unit> {
        val data = mapOf(
            "email" to email,
            "role" to "student",
            "name" to name,
            "createdAt" to System.currentTimeMillis()
        )
        profiles[uid] = data
        lastCreatedProfile = Pair(uid, data)
        return Result.success(Unit)
    }
}

/**
 * In-memory test double for BackendClient.
 */
class FakeBackendClient : BackendClient {
    var responseMap: Map<String, Any?>? = null
    var shouldFail: Boolean = false
    var failureCode: String = "INTERNAL"
    var failureMessage: String = "Server error"
    var lastEndpoint: String? = null
    var lastPayload: Map<String, Any?>? = null

    override suspend fun post(endpoint: String, payload: Map<String, Any?>): Result<Map<String, Any?>> {
        lastEndpoint = endpoint
        lastPayload = payload
        if (shouldFail) {
            return Result.failure(BackendApiException(failureCode, failureMessage))
        }
        return Result.success(responseMap ?: emptyMap())
    }
}
