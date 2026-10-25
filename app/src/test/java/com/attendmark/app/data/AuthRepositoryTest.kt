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
 *
 * Confirms that profiles and roles are created strictly by the authoritative backend
 * and that client-side Firestore profile creation is prohibited.
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
    fun login_whenBackendFailsAndNoProfileExists_returnsBootstrapPendingWithoutCreatingProfile() = runBlocking {
        fakeAuthProvider.configureUser("new@test.com", "pass123", "uid-new", "New User")

        val result = repository.login("new@test.com", "pass123")
        // Must NOT create profile directly in Firestore; must prompt retry
        assertTrue(result is AuthResult.BootstrapPending)
        val pending = result as AuthResult.BootstrapPending
        assertEquals("new@test.com", pending.email)
        assertEquals("New User", pending.name)
        assertNull("No profile must be written to Firestore client-side", fakeProfileStore.getProfile("uid-new"))
        // User remains signed in to Auth
        assertTrue(fakeAuthProvider.getCurrentUser() != null)
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
    fun login_whenBackendFails_fallsBackToExistingFirestoreProfile() = runBlocking {
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

    // ---- Student Registration Tests ----

    @Test
    fun register_withBlankEmailOrPassword_returnsError() = runBlocking {
        val result1 = repository.register("", "pass123", "pass123")
        assertTrue(result1 is AuthResult.Error)
        assertEquals("Please enter email and password", (result1 as AuthResult.Error).message)

        val result2 = repository.register("21cs01@test.com", "", "")
        assertTrue(result2 is AuthResult.Error)
        assertEquals("Please enter email and password", (result2 as AuthResult.Error).message)
    }

    @Test
    fun register_withNonDigitEmailPrefix_returnsError() = runBlocking {
        // Faculty email or student without roll prefix must be rejected
        val result = repository.register("student@test.com", "pass123", "pass123")
        assertTrue(result is AuthResult.Error)
        assertEquals("Student email must start with your 2-digit roll/year prefix (e.g. 21...)", (result as AuthResult.Error).message)

        val result2 = repository.register("faculty@univ.edu", "pass123", "pass123")
        assertTrue(result2 is AuthResult.Error)
        assertEquals("Student email must start with your 2-digit roll/year prefix (e.g. 21...)", (result2 as AuthResult.Error).message)
    }

    @Test
    fun register_withShortPassword_returnsError() = runBlocking {
        val result = repository.register("21cs01@test.com", "12345", "12345")
        assertTrue(result is AuthResult.Error)
        assertEquals("Password must be at least 6 characters", (result as AuthResult.Error).message)
    }

    @Test
    fun register_withMismatchedPasswords_returnsError() = runBlocking {
        val result = repository.register("21cs01@test.com", "secret123", "secret456")
        assertTrue(result is AuthResult.Error)
        assertEquals("Passwords do not match", (result as AuthResult.Error).message)
    }

    @Test
    fun register_withValidStudentEmail_successCallsSignUpAndBootstrapProfile() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            responseMap = mapOf(
                "role" to "student",
                "name" to "John Doe",
                "uid" to "uid-new-student",
                "email" to "24cs099@univ.edu"
            )
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )

        val result = customRepo.register("24cs099@univ.edu", "password123", "password123", "John Doe")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("John Doe", user.name)
        assertEquals("24cs099@univ.edu", user.email)
        assertEquals("bootstrap-profile", fakeBackend.lastEndpoint)
        assertEquals("John Doe", (fakeBackend.lastPayload?.get("name")))
    }

    @Test
    fun register_whenBackendReturnsFacultyRole_rejectsAndSignsOut() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            responseMap = mapOf(
                "role" to "faculty",
                "name" to "Dr. Faculty",
                "uid" to "uid-fac-mismatch",
                "email" to "21fac@univ.edu"
            )
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )

        val result = customRepo.register("21fac@univ.edu", "password123", "password123")
        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).message.contains("Faculty account detected"))
        assertTrue(fakeAuthProvider.signOutCalled)
    }

    @Test
    fun register_whenBackendFailsAndNoProfileExists_returnsBootstrapPendingWithoutCreatingProfile() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            shouldFail = true
            failureCode = "UNAVAILABLE"
            failureMessage = "Server offline"
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )

        val result = customRepo.register("23bcs05@univ.edu", "password123", "password123", "Alice Smith")
        assertTrue(result is AuthResult.BootstrapPending)
        val pending = result as AuthResult.BootstrapPending
        assertEquals("23bcs05@univ.edu", pending.email)
        assertEquals("Alice Smith", pending.name)

        // Verifies NO client-side profile write occurred
        assertNull("No profile document should be written client-side in Firestore", fakeProfileStore.getProfile("uid-1"))
        // User session is preserved
        assertTrue("User must remain authenticated in Auth to allow retry", fakeAuthProvider.getCurrentUser() != null)
    }

    // ---- Retry Profile Bootstrap Tests ----

    @Test
    fun retryBootstrapProfile_whenBackendRecovers_returnsSuccessWithAuthoritativeRole() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            responseMap = mapOf(
                "role" to "student",
                "name" to "Alice Smith",
                "uid" to "uid-retry-1",
                "email" to "23bcs05@univ.edu"
            )
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )
        fakeAuthProvider.configureUser("23bcs05@univ.edu", "pass123", "uid-retry-1", "Alice Smith")
        fakeAuthProvider.signIn("23bcs05@univ.edu", "pass123")

        val result = customRepo.retryBootstrapProfile("Alice Smith")
        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).user
        assertEquals("student", user.role)
        assertEquals("Alice Smith", user.name)
        assertEquals("23bcs05@univ.edu", user.email)
    }

    @Test
    fun retryBootstrapProfile_whenBackendReturnsFaculty_rejectsAndSignsOut() = runBlocking {
        val fakeBackend = FakeBackendClient().apply {
            responseMap = mapOf(
                "role" to "faculty",
                "name" to "Prof. Smith",
                "uid" to "uid-retry-fac",
                "email" to "smith@univ.edu"
            )
        }
        val customRepo = AuthRepository(
            authProvider = fakeAuthProvider,
            profileStore = fakeProfileStore,
            backendClient = fakeBackend
        )
        fakeAuthProvider.configureUser("smith@univ.edu", "pass123", "uid-retry-fac", "Prof. Smith")
        fakeAuthProvider.signIn("smith@univ.edu", "pass123")

        val result = customRepo.retryBootstrapProfile("Prof. Smith")
        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).message.contains("Faculty account detected"))
        assertTrue(fakeAuthProvider.signOutCalled)
    }

    @Test
    fun retryBootstrapProfile_whenNotAuthenticated_returnsError() = runBlocking {
        val result = repository.retryBootstrapProfile("Nobody")
        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).message.contains("No authenticated session"))
    }

    // ---- Emulator Config Tests ----

    @Test
    fun useEmulator_whenHostIsCloud_doesNotThrow() {
        AuthRepository.useEmulator("cloud", 9099, 8080)
        AuthRepository.useEmulator("none", 9099, 8080)
        AuthRepository.useEmulator("", 9099, 8080)
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

    override suspend fun signUp(email: String, password: String): Result<Pair<String, String?>> {
        failureMessage?.let {
            return Result.failure(Exception(it))
        }
        val existing = users.keys.any { it.first == email }
        if (existing) {
            return Result.failure(Exception("An account already exists with this email address."))
        }
        val uid = "uid-${users.size + 1}"
        val userPair = Pair(uid, null)
        users[Pair(email, password)] = userPair
        currentUser = userPair
        return Result.success(userPair)
    }

    override fun signOut() {
        signOutCalled = true
        currentUser = null
    }

    override fun getCurrentUser(): Pair<String, String?>? = currentUser
}

/**
 * In-memory test double for UserProfileStore (READ ONLY).
 */
class FakeUserProfileStore : UserProfileStore {
    private val profiles = mutableMapOf<String, Map<String, Any>>()

    fun setProfile(uid: String, data: Map<String, Any>) {
        profiles[uid] = data
    }

    fun getProfile(uid: String): Map<String, Any>? = profiles[uid]

    override suspend fun getUserProfile(uid: String): Result<Map<String, Any>?> {
        return Result.success(profiles[uid])
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
