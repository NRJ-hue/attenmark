package com.attendmark.app.data

import android.util.Log
import com.attendmark.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exception representing a typed error returned by the AttendIQ backend API.
 */
class BackendApiException(
    val code: String,
    message: String
) : Exception(message)

/**
 * Interface for backend API client to allow clean mocking in JVM unit tests.
 */
interface BackendClient {
    suspend fun post(endpoint: String, payload: Map<String, Any?>): Result<Map<String, Any?>>
}

/**
 * Default implementation of BackendClient connecting to Render cloud service (or local server).
 * Sends authenticated HTTPS requests with Bearer Firebase Auth ID tokens.
 */
class DefaultBackendClient(
    private val baseUrl: String = BuildConfig.BACKEND_URL
) : BackendClient {

    companion object {
        private const val TAG = "BackendApiClient"
        private const val CONNECT_TIMEOUT_MS = 15000 // 15s to allow for Render free-tier cold spin up
        private const val READ_TIMEOUT_MS = 25000    // 25s for processing complex requests
    }

    override suspend fun post(endpoint: String, payload: Map<String, Any?>): Result<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                    ?: return@withContext Result.failure(
                        BackendApiException("UNAUTHENTICATED", "User must be authenticated with Firebase.")
                    )

                val tokenResult = try {
                    currentUser.getIdToken(false).await()
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        BackendApiException("UNAUTHENTICATED", "Failed to retrieve Firebase ID token: ${e.message}")
                    )
                }

                val idToken = tokenResult.token
                    ?: return@withContext Result.failure(
                        BackendApiException("UNAUTHENTICATED", "Retrieved Firebase ID token is null.")
                    )

                val cleanBase = baseUrl.trimEnd('/')
                val urlString = if (endpoint.startsWith("api/")) {
                    "$cleanBase/$endpoint"
                } else {
                    "$cleanBase/api/$endpoint"
                }

                val url = URL(urlString)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $idToken")
                }

                // Wrap payload in { "data": payload } for seamless callable parity
                val requestJson = JSONObject()
                requestJson.put("data", mapToJsonObject(payload))

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(requestJson.toString())
                    writer.flush()
                }

                val statusCode = conn.responseCode
                val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                        reader.readText()
                    }
                } ?: ""

                if (statusCode in 200..299) {
                    val rootJson = if (responseText.isNotBlank()) JSONObject(responseText) else JSONObject()
                    // If root contains "data", return data map; otherwise return root map
                    val dataJson = if (rootJson.has("data") && !rootJson.isNull("data") && rootJson.get("data") is JSONObject) {
                        rootJson.getJSONObject("data")
                    } else {
                        rootJson
                    }
                    val resultMap = jsonObjectToMap(dataJson)
                    Result.success(resultMap)
                } else {
                    var errorCode = "UNKNOWN"
                    var errorMessage = "Backend API returned HTTP $statusCode"

                    try {
                        if (responseText.isNotBlank()) {
                            val errJson = JSONObject(responseText)
                            if (errJson.has("error")) {
                                val errObj = errJson.getJSONObject("error")
                                errorCode = errObj.optString("code", errorCode)
                                errorMessage = errObj.optString("message", errorMessage)
                            } else if (errJson.has("message")) {
                                errorMessage = errJson.getString("message")
                            }
                        }
                    } catch (_: Exception) {}

                    Log.w(TAG, "Backend error ($statusCode): [$errorCode] $errorMessage")
                    Result.failure(BackendApiException(errorCode, errorMessage))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Request to backend failed: ${e.message}")
                Result.failure(e)
            }
        }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    json.put(key, mapToJsonObject(value as Map<String, Any?>))
                }
                is List<*> -> {
                    val arr = JSONArray()
                    for (item in value) {
                        when (item) {
                            null -> arr.put(JSONObject.NULL)
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                arr.put(mapToJsonObject(item as Map<String, Any?>))
                            }
                            else -> arr.put(item)
                        }
                    }
                    json.put(key, arr)
                }
                else -> json.put(key, value)
            }
        }
        return json
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            map[key] = when {
                value == null || value == JSONObject.NULL -> null
                value is JSONObject -> jsonObjectToMap(value)
                value is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            val value = arr.opt(i)
            list.add(
                when {
                    value == null || value == JSONObject.NULL -> null
                    value is JSONObject -> jsonObjectToMap(value)
                    value is JSONArray -> jsonArrayToList(value)
                    else -> value
                }
            )
        }
        return list
    }
}

/**
 * Global singleton access to BackendClient.
 */
object BackendApiClient : BackendClient by DefaultBackendClient()
