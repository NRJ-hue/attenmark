package com.attendmark.app.data

import kotlinx.coroutines.delay

/**
 * Dummy credentials for MVP testing.
 * No real authentication server needed for MVP.
 */
object DummyAuth {

    data class User(
        val email: String,
        val role: String, // "student" or "faculty"
        val name: String
    )

    /**
     * Validates credentials. Returns the user if valid, null if invalid.
     */
    suspend fun login(email: String, password: String): User? {
        delay(800) // Simulate network delay
        
        return if (email == "faculty@test.com" && password == "faculty123") {
            User(email, "faculty", "Prof. Smith")
        } else if (email.contains("student") && password.isNotBlank()) {
            // Automatically generate a student account for ANY email containing "student"
            val name = email.substringBefore("@").replaceFirstChar { it.uppercase() }
            User(email, "student", name)
        } else {
            null
        }
    }
}
