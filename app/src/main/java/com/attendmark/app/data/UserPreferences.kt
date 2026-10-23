package com.attendmark.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "attend_mark_prefs")

class UserPreferences(private val context: Context) {

    companion object {
        val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        val KEY_USER_ROLE = stringPreferencesKey("user_role")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_USER_UID = stringPreferencesKey("user_uid")
        val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val KEY_FACE_ENROLLED = booleanPreferencesKey("face_enrolled")
        // Store face embedding as JSON string
        val KEY_FACE_EMBEDDING = stringPreferencesKey("face_embedding")
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_IS_LOGGED_IN] ?: false
    }

    val userEmail: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_EMAIL] ?: ""
    }

    val userRole: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_ROLE] ?: ""
    }

    val userName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_NAME] ?: ""
    }

    val userUid: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_UID] ?: ""
    }

    val isFaceEnrolled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FACE_ENROLLED] ?: false
    }

    val faceEmbedding: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FACE_EMBEDDING] ?: ""
    }

    suspend fun saveLoginState(email: String, role: String, name: String, uid: String = "") {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_EMAIL] = email
            prefs[KEY_USER_ROLE] = role
            prefs[KEY_USER_NAME] = name
            prefs[KEY_USER_UID] = uid
            prefs[KEY_IS_LOGGED_IN] = true
        }
    }

    suspend fun saveFaceEmbedding(embeddingJson: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FACE_EMBEDDING] = embeddingJson
            prefs[KEY_FACE_ENROLLED] = true
        }
    }

    suspend fun clearFaceEnrollment() {
        context.dataStore.edit { prefs ->
            prefs[KEY_FACE_EMBEDDING] = ""
            prefs[KEY_FACE_ENROLLED] = false
        }
    }

    suspend fun logout() {
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_LOGGED_IN] = false
            prefs[KEY_USER_EMAIL] = ""
            prefs[KEY_USER_ROLE] = ""
            prefs[KEY_USER_NAME] = ""
            prefs[KEY_USER_UID] = ""
            // Keep face enrollment on logout so student doesn't need to re-enroll
        }
    }
}
