package com.attendmark.app.data

import android.util.Log
import com.attendmark.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions

/**
 * Manages configuration and connection to the Firebase Emulator Suite for local development.
 * Automatically disabled in release builds to prevent pointing to local development endpoints.
 */
object FirebaseEmulatorConfig {
    /**
     * Flag to control connecting to the Firebase Emulator Suite during development.
     * Defaults to true in debug builds, false in release.
     */
    var isEmulatorEnabled: Boolean = BuildConfig.DEBUG &&
        BuildConfig.EMULATOR_HOST.isNotBlank() &&
        !BuildConfig.EMULATOR_HOST.trim().equals("cloud", ignoreCase = true) &&
        !BuildConfig.EMULATOR_HOST.trim().equals("none", ignoreCase = true)

    /**
     * Default host: "10.0.2.2" maps to localhost from the Android Studio emulator.
     * In debug builds, can be overridden at build time via -PemulatorHost=127.0.0.1 for USB-connected physical phones using adb reverse.
     */
    var emulatorHost: String = if (BuildConfig.DEBUG && BuildConfig.EMULATOR_HOST.isNotEmpty()) {
        BuildConfig.EMULATOR_HOST.trim()
    } else {
        "10.0.2.2"
    }
    const val AUTH_PORT = 9099
    const val FIRESTORE_PORT = 8080
    const val FUNCTIONS_PORT = 5001

    private var isConfigured = false

    fun setup(enabled: Boolean = isEmulatorEnabled, host: String = emulatorHost) {
        if (!enabled || isConfigured) return
        if (host.equals("cloud", ignoreCase = true) || host.equals("none", ignoreCase = true) || host.isBlank()) {
            return
        }
        try {
            FirebaseAuth.getInstance().useEmulator(host, AUTH_PORT)
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_PORT)
            FirebaseFunctions.getInstance().useEmulator(host, FUNCTIONS_PORT)
            isConfigured = true
            Log.i("FirebaseEmulator", "Connected to Firebase Emulator (Auth: $host:$AUTH_PORT, Firestore: $host:$FIRESTORE_PORT, Functions: $host:$FUNCTIONS_PORT)")
        } catch (e: Exception) {
            Log.w("FirebaseEmulator", "Could not configure emulator: ${e.message}")
        }
    }
}
