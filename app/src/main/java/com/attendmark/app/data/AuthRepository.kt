package com.attendmark.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
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
    data class Error(val message: String) : AuthResult()
}

/**
 * Interface representing authentication operations.
 * Allows pure JVM unit testing without mocking static Firebase instances.
 */
interface AuthProvider {
    suspend fun signIn(email: String, password: String): Result<Pair<String, String?>>
    fun signOut()
    fun getCurrentUser(): Pair<String, String?>?
}

/**
 * Interface representing user profile retrieval and persistence in Firestore.
 */
interface UserProfileStore {
    suspend fun getUserProfile(uid: String): Result<Map<String, Any>?>
    suspend fun createInitialStudentProfile(uid: String, email: String, name: String): Result<Unit>
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
 * Default implementation using real Firebase Firestore.
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

    override suspend fun createInitialStudentProfile(
        uid: String,
        email: String,
        name: String
    ): Result<Unit> {
        return try {
            // Self-registered profiles can ONLY be "student"
            val profile = hashMapOf(
                "email" to email,
                "name" to name,
                "role" to "student", // Strictly student
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection("users").document(uid).set(profile).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Repository responsible for handling user authentication and role resolution.
 * Enforces that faculty roles can only be granted via backend/admin records in users/{uid}.
 */
class AuthRepository(
    private val authProvider: AuthProvider = DefaultFirebaseAuth(),
    private val profileStore: UserProfileStore = DefaultFirestoreProfileStore()
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

        // Retrieve user document from Firestore to resolve authorized role
        val profileResult = profileStore.getUserProfile(uid)
        if (profileResult.isFailure) {
            val err = profileResult.exceptionOrNull()?.localizedMessage ?: "Failed to verify user profile"
            return AuthResult.Error("Profile check error: $err")
        }

        val profileData = profileResult.getOrNull()
        val role: String
        val name: String

        if (profileData != null) {
            val rawRole = (profileData["role"] as? String)?.lowercase()?.trim()
            // Strictly check for "faculty"; anything else defaults to "student"
            role = if (rawRole == "faculty") "faculty" else "student"
            name = (profileData["name"] as? String)
                ?: displayName
                ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        } else {
            // No profile document found in users/{uid}.
            // Create default student profile. User cannot assign themselves faculty role.
            role = "student"
            name = displayName
                ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }

            profileStore.createInitialStudentProfile(uid, cleanEmail, name)
        }

        return AuthResult.Success(
            AuthUser(
                uid = uid,
                email = cleanEmail,
                name = name,
                role = role
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
            is FirebaseAuthInvalidUserException -> "No account found with this email."
            is FirebaseAuthInvalidCredentialsException -> "Invalid email or password."
            else -> {
                val raw = exception?.localizedMessage ?: "Sign in failed"
                if (raw.contains("network", ignoreCase = true)) {
                    "Network error. Please check your internet connection."
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
        fun useEmulator(host: String = "10.0.2.2", authPort: Int = 9099, firestorePort: Int = 8080) {
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
