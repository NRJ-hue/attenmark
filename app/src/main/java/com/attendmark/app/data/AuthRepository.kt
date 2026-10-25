package com.attendmark.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class AuthUser(
    val uid: String,
    val email: String,
    val name: String,
    val role: String // "student" or "faculty"
)

sealed class AuthResult {
    data class Success(val user: AuthUser) : AuthResult()
    data class BootstrapPending(val email: String, val name: String, val message: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/**
 * Interface representing authentication operations.
 * Allows pure JVM unit testing without mocking static Firebase instances.
 */
interface AuthProvider {
    suspend fun signIn(email: String, password: String): Result<Pair<String, String?>>
    suspend fun signUp(email: String, password: String): Result<Pair<String, String?>>
    fun signOut()
    fun getCurrentUser(): Pair<String, String?>?
}

/**
 * Interface representing user profile retrieval in Firestore (READ ONLY).
 * Direct client creation of profiles in Firestore is prohibited; all writes
 * are performed authoritatively by the backend via Firebase Admin SDK.
 */
interface UserProfileStore {
    suspend fun getUserProfile(uid: String): Result<Map<String, Any>?>
}

/**
 * Default implementation using real Firebase Auth.
 */
class DefaultFirebaseAuth(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : AuthProvider {
    override suspend fun signIn(email: String, password: String): Result<Pair<String, String?>> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                Result.success(Pair(user.uid, user.displayName))
            } else {
                Result.failure(Exception("Authentication succeeded but user is null"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signUp(email: String, password: String): Result<Pair<String, String?>> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                Result.success(Pair(user.uid, user.displayName))
            } else {
                Result.failure(Exception("Registration succeeded but user is null"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun signOut() {
        try {
            auth.signOut()
        } catch (_: Exception) {}
    }

    override fun getCurrentUser(): Pair<String, String?>? {
        val user = auth.currentUser ?: return null
        return Pair(user.uid, user.displayName)
    }
}

/**
 * Default implementation using real Firebase Firestore (READ ONLY).
 */
class DefaultFirestoreProfileStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : UserProfileStore {
    override suspend fun getUserProfile(uid: String): Result<Map<String, Any>?> {
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            if (doc.exists()) {
                Result.success(doc.data)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Repository responsible for handling user authentication and authoritative role resolution.
 * All profile creation and role assignment are strictly delegated to the backend API.
 * The client NEVER creates or updates profiles directly in Firestore.
 */
class AuthRepository(
    private val authProvider: AuthProvider = DefaultFirebaseAuth(),
    private val profileStore: UserProfileStore = DefaultFirestoreProfileStore(),
    private val backendClient: BackendClient = BackendApiClient
) {

    suspend fun login(email: String, password: String): AuthResult {
        val cleanEmail = email.trim()

        if (cleanEmail.isBlank() || password.isEmpty()) {
            return AuthResult.Error("Please enter email and password")
        }

        val authResult = authProvider.signIn(cleanEmail, password)
        if (authResult.isFailure) {
            val exception = authResult.exceptionOrNull()
            return mapAuthError(exception)
        }

        val (uid, displayName) = authResult.getOrNull()
            ?: return AuthResult.Error("Authentication failed: User details missing")

        // 1. Authoritatively resolve role via backend API if reachable
        var role: String? = null
        var name: String? = null
        var backendError: String? = null

        val bootstrapResult = try {
            backendClient.post(
                "bootstrap-profile",
                mapOf("name" to (displayName ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }))
            )
        } catch (e: Exception) {
            backendError = e.localizedMessage
            Result.failure(Exception("Backend unreachable: ${e.message}"))
        }

        if (bootstrapResult.isSuccess) {
            val resData = bootstrapResult.getOrNull()
            if (resData != null) {
                role = (resData["role"] as? String)?.lowercase()?.trim()
                name = resData["name"] as? String
            }
        } else {
            backendError = bootstrapResult.exceptionOrNull()?.localizedMessage ?: backendError ?: "Unable to reach profile service"
        }

        // 2. If backend was unreachable, fall back to existing Firestore profile (READ ONLY)
        if (role == null) {
            val profileResult = profileStore.getUserProfile(uid)
            val profileData = profileResult.getOrNull()
            if (profileData != null) {
                val rawRole = (profileData["role"] as? String)?.lowercase()?.trim()
                role = if (rawRole == "faculty") "faculty" else "student"
                name = (profileData["name"] as? String)
                    ?: displayName
                    ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
            }
        }

        // 3. If profile still doesn't exist and backend failed, keep user signed in and prompt to retry setup
        if (role == null) {
            val resolvedName = displayName ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
            return AuthResult.BootstrapPending(
                email = cleanEmail,
                name = resolvedName,
                message = "Account authenticated, but profile setup failed: $backendError. Tap 'Retry Profile Setup' to try again."
            )
        }

        val resolvedRole = if (role == "faculty") "faculty" else "student"
        val resolvedName = name ?: displayName ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }

        return AuthResult.Success(
            AuthUser(
                uid = uid,
                email = cleanEmail,
                name = resolvedName,
                role = resolvedRole
            )
        )
    }

    /**
     * Registers a new student account using Firebase Authentication.
     * Enforces the student 2-digit roll prefix, minimum 6 characters for password,
     * matching confirmation, and calls authoritative backend profile bootstrap.
     *
     * Never writes profile documents directly to Firestore.
     */
    suspend fun register(
        email: String,
        password: String,
        confirmPassword: String,
        name: String = ""
    ): AuthResult {
        val cleanEmail = email.trim()

        if (cleanEmail.isBlank() || password.isEmpty()) {
            return AuthResult.Error("Please enter email and password")
        }

        // Student email must start with two ASCII digits
        if (cleanEmail.length < 2 || !cleanEmail[0].isDigit() || !cleanEmail[1].isDigit()) {
            return AuthResult.Error("Student email must start with your 2-digit roll/year prefix (e.g. 21...)")
        }

        if (password.length < 6) {
            return AuthResult.Error("Password must be at least 6 characters")
        }

        if (password != confirmPassword) {
            return AuthResult.Error("Passwords do not match")
        }

        val authResult = authProvider.signUp(cleanEmail, password)
        if (authResult.isFailure) {
            val exception = authResult.exceptionOrNull()
            return mapAuthError(exception)
        }

        val (uid, displayName) = authResult.getOrNull()
            ?: return AuthResult.Error("Registration succeeded but user details missing")

        val resolvedName = name.trim().ifEmpty {
            displayName ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        }

        // 1. Authoritatively resolve role and bootstrap profile via backend API if reachable
        var role: String? = null
        var serverName: String? = null
        var backendError: String? = null

        val bootstrapResult = try {
            backendClient.post(
                "bootstrap-profile",
                mapOf("name" to resolvedName)
            )
        } catch (e: Exception) {
            backendError = e.localizedMessage
            Result.failure(Exception("Backend unreachable: ${e.message}"))
        }

        if (bootstrapResult.isSuccess) {
            val resData = bootstrapResult.getOrNull()
            if (resData != null) {
                role = (resData["role"] as? String)?.lowercase()?.trim()
                serverName = resData["name"] as? String
            }
        } else {
            backendError = bootstrapResult.exceptionOrNull()?.localizedMessage ?: backendError ?: "Unable to reach profile service"
        }

        // 2. If backend was unreachable, check if profile already exists in Firestore (READ ONLY)
        if (role == null) {
            val profileResult = profileStore.getUserProfile(uid)
            val profileData = profileResult.getOrNull()
            if (profileData != null) {
                role = (profileData["role"] as? String)?.lowercase()?.trim()
                serverName = profileData["name"] as? String
            }
        }

        // 3. If profile still doesn't exist and backend failed:
        // Keep the Firebase Auth account signed in, but NEVER create profile client-side.
        if (role == null) {
            return AuthResult.BootstrapPending(
                email = cleanEmail,
                name = resolvedName,
                message = "Account registered, but profile initialization failed: $backendError. Tap 'Retry Profile Setup' to complete registration."
            )
        }

        val finalRole = role
        val finalName = serverName ?: resolvedName

        // If backend classified the user as faculty, do not permit student registration
        if (finalRole == "faculty") {
            authProvider.signOut()
            return AuthResult.Error("Faculty account detected. Student registration is for students only. Please use the Faculty Web Portal at https://attendmark-c2f07.web.app")
        }

        return AuthResult.Success(
            AuthUser(
                uid = uid,
                email = cleanEmail,
                name = finalName,
                role = finalRole
            )
        )
    }

    /**
     * Retries authoritative backend profile bootstrap for an already authenticated session.
     */
    suspend fun retryBootstrapProfile(name: String = ""): AuthResult {
        val currentUser = authProvider.getCurrentUser()
            ?: return AuthResult.Error("No authenticated session found. Please sign in again.")

        val uid = currentUser.first
        val displayName = currentUser.second
        val email = try {
            FirebaseAuth.getInstance().currentUser?.email ?: ""
        } catch (_: Exception) {
            ""
        }

        val resolvedName = name.trim().ifEmpty {
            displayName ?: email.substringBefore("@").replaceFirstChar { it.uppercase() }
        }

        var role: String? = null
        var serverName: String? = null
        var backendError: String? = null

        val bootstrapResult = try {
            backendClient.post(
                "bootstrap-profile",
                mapOf("name" to resolvedName)
            )
        } catch (e: Exception) {
            backendError = e.localizedMessage
            Result.failure(e)
        }

        var resolvedEmail = email

        if (bootstrapResult.isSuccess) {
            val resData = bootstrapResult.getOrNull()
            if (resData != null) {
                role = (resData["role"] as? String)?.lowercase()?.trim()
                serverName = resData["name"] as? String
                (resData["email"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    resolvedEmail = it
                }
            }
        } else {
            backendError = bootstrapResult.exceptionOrNull()?.localizedMessage ?: backendError ?: "Profile setup failed"
        }

        // Check if profile exists in Firestore (READ ONLY)
        if (role == null) {
            val profileResult = profileStore.getUserProfile(uid)
            val profileData = profileResult.getOrNull()
            if (profileData != null) {
                role = (profileData["role"] as? String)?.lowercase()?.trim()
                serverName = profileData["name"] as? String
                (profileData["email"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    resolvedEmail = it
                }
            }
        }

        if (role == null) {
            return AuthResult.BootstrapPending(
                email = resolvedEmail,
                name = resolvedName,
                message = "Profile setup retry failed: $backendError. Tap 'Retry Profile Setup' to try again."
            )
        }

        val finalRole = role
        val finalName = serverName ?: resolvedName

        if (finalRole == "faculty") {
            authProvider.signOut()
            return AuthResult.Error("Faculty account detected. Student registration is for students only. Please use the Faculty Web Portal at https://attendmark-c2f07.web.app")
        }

        return AuthResult.Success(
            AuthUser(
                uid = uid,
                email = resolvedEmail,
                name = finalName,
                role = finalRole
            )
        )
    }

    fun logout() {
        authProvider.signOut()
    }

    fun getCurrentUser(): Pair<String, String?>? {
        return authProvider.getCurrentUser()
    }

    private fun mapAuthError(exception: Throwable?): AuthResult.Error {
        val message = when (exception) {
            is FirebaseAuthUserCollisionException -> "An account already exists with this email address."
            is FirebaseAuthWeakPasswordException -> "Password must be at least 6 characters."
            is FirebaseAuthInvalidUserException -> "No account found with this email."
            is FirebaseAuthInvalidCredentialsException -> "Invalid email or password."
            else -> {
                val raw = exception?.localizedMessage ?: "Authentication failed"
                if (raw.contains("already in use", ignoreCase = true) || raw.contains("email-already-in-use", ignoreCase = true)) {
                    "An account already exists with this email address."
                } else if (raw.contains("network", ignoreCase = true)) {
                    "Network error. Please check your internet connection."
                } else if (raw.contains("password", ignoreCase = true) && raw.contains("at least", ignoreCase = true)) {
                    "Password must be at least 6 characters."
                } else if (raw.contains("password", ignoreCase = true) || raw.contains("credential", ignoreCase = true)) {
                    "Invalid email or password."
                } else {
                    raw
                }
            }
        }
        return AuthResult.Error(message)
    }

    companion object {
        private var isEmulatorConfigured = false

        /**
         * Connects Firebase Auth and Firestore to local emulators for development/testing.
         */
        fun useEmulator(host: String = FirebaseEmulatorConfig.emulatorHost, authPort: Int = 9099, firestorePort: Int = 8080) {
            if (host.equals("cloud", ignoreCase = true) || host.equals("none", ignoreCase = true) || host.isBlank()) {
                return
            }
            if (!isEmulatorConfigured) {
                try {
                    FirebaseAuth.getInstance().useEmulator(host, authPort)
                    FirebaseFirestore.getInstance().useEmulator(host, firestorePort)
                    isEmulatorConfigured = true
                } catch (_: Exception) {}
            }
        }
    }
}
