package com.attendmark.app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Manages configuration and connection to the Firebase Emulator Suite for local development.
 */
object FirebaseEmulatorConfig {
    /**
     * Flag to control connecting to the Firebase Emulator Suite during development.
     * Defaults to true for local testing. Set to false to target live Firebase.
     */
    var isEmulatorEnabled: Boolean = true

    /**
     * Default host: "10.0.2.2" maps to localhost from the Android Studio emulator.
     * Can be customized to your computer's local network IP when testing on a physical phone.
     */
    var emulatorHost: String = "10.0.2.2"
    const val AUTH_PORT = 9099
    const val FIRESTORE_PORT = 8080

    private var isConfigured = false

    fun setup(enabled: Boolean = isEmulatorEnabled, host: String = emulatorHost) {
        if (!enabled || isConfigured) return
        try {
            FirebaseAuth.getInstance().useEmulator(host, AUTH_PORT)
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_PORT)
            isConfigured = true
            Log.i("FirebaseEmulator", "Connected to Firebase Emulator (Auth: $host:$AUTH_PORT, Firestore: $host:$FIRESTORE_PORT)")
        } catch (e: Exception) {
            Log.w("FirebaseEmulator", "Could not configure emulator: ${e.message}")
        }
    }
}
