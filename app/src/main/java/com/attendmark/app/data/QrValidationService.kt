package com.attendmark.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * Result of validating the format and payload of a scanned QR code.
 */
sealed class TokenFormatResult {
    data class Valid(val token: String) : TokenFormatResult()
    data class Invalid(val reason: String) : TokenFormatResult()
}

/**
 * Result of server-side validation against validateQrToken Cloud Function.
 */
sealed class QrValidationResult {
    data class Success(
        val sessionId: String,
        val courseCode: String,
        val attempt: Int,
        val retryRemaining: Int
    ) : QrValidationResult()

    data class Expired(val message: String) : QrValidationResult()
    data class SessionInactive(val message: String) : QrValidationResult()
    data class AlreadyRecorded(val message: String) : QrValidationResult()
    data class RetriesExhausted(val message: String) : QrValidationResult()
    data class PermissionDenied(val message: String) : QrValidationResult()
    data class NotAuthenticated(val message: String) : QrValidationResult()
    data class NotFound(val message: String) : QrValidationResult()
    data class InvalidArgument(val message: String) : QrValidationResult()
    data class Error(val message: String) : QrValidationResult()
}

/**
 * Service to validate QR token payloads and invoke backend validation.
 */
class QrValidationService(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    companion object {
        private val HEX_TOKEN_REGEX = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Pure function to validate the raw decoded QR string.
         * Ensures no external URLs or arbitrary non-token payloads are processed.
         */
        fun validateTokenPayload(rawPayload: String?): TokenFormatResult {
            if (rawPayload.isNullOrBlank()) {
                return TokenFormatResult.Invalid("QR code payload is empty.")
            }

            val trimmed = rawPayload.trim()

            // Reject URLs, external links, and web schemes
            val lower = trimmed.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("www.") || lower.startsWith("ftp://") ||
                lower.startsWith("sidecar://") || lower.contains("://")
            ) {
                return TokenFormatResult.Invalid(
                    "External URLs and web links are not accepted. Scan an authentic AttendIQ classroom code."
                )
            }

            // AttendIQ tokens are strictly 32-byte (64 hex character) cryptographically opaque tokens
            if (!HEX_TOKEN_REGEX.matches(trimmed)) {
                return TokenFormatResult.Invalid(
                    "Invalid token format: Must be a 64-character security token."
                )
            }

            return TokenFormatResult.Valid(trimmed)
        }

        /**
         * Map Firebase Functions error code string to typed QrValidationResult.
         */
        fun mapCodeStringToResult(codeName: String, message: String): QrValidationResult {
            val normalized = codeName.trim().uppercase().replace("-", "_")
            return when (normalized) {
                "UNAUTHENTICATED" ->
                    QrValidationResult.NotAuthenticated("Authentication required: $message")
                "PERMISSION_DENIED" ->
                    QrValidationResult.PermissionDenied(message)
                "NOT_FOUND" ->
                    QrValidationResult.NotFound(message)
                "DEADLINE_EXCEEDED" ->
                    QrValidationResult.Expired(message)
                "FAILED_PRECONDITION" ->
                    QrValidationResult.SessionInactive(message)
                "ALREADY_EXISTS" ->
                    QrValidationResult.AlreadyRecorded(message)
                "RESOURCE_EXHAUSTED" ->
                    QrValidationResult.RetriesExhausted(message)
                "INVALID_ARGUMENT" ->
                    QrValidationResult.InvalidArgument(message)
                else ->
                    QrValidationResult.Error(message)
            }
        }

        /**
         * Map Firebase Functions error code to typed QrValidationResult.
         */
        fun mapCodeToResult(code: FirebaseFunctionsException.Code, message: String): QrValidationResult {
            return mapCodeStringToResult(code.name, message)
        }

        /**
         * Parse Firebase Functions exception into typed QrValidationResult.
         */
        fun mapFunctionsException(e: Exception): QrValidationResult {
            if (e is FirebaseFunctionsException) {
                return mapCodeStringToResult(e.code.name, e.message ?: "Backend error")
            }
            return QrValidationResult.Error(e.message ?: "Network or connection error")
        }
    }

    /**
     * Call the validateQrToken Cloud Function with the scanned token, course code, and session ID.
     */
    suspend fun validateToken(
        tokenId: String,
        courseCode: String,
        sessionId: String
    ): QrValidationResult {
        // Local format validation first
        when (val formatResult = validateTokenPayload(tokenId)) {
            is TokenFormatResult.Invalid -> return QrValidationResult.InvalidArgument(formatResult.reason)
            is TokenFormatResult.Valid -> { /* Valid format, proceed */ }
        }

        return try {
            val data = hashMapOf(
                "tokenId" to tokenId.trim(),
                "courseCode" to courseCode.trim(),
                "sessionId" to sessionId.trim()
            )

            val callable = functions.getHttpsCallable("validateQrToken")
            val result = callable.call(data).await()

            @Suppress("UNCHECKED_CAST")
            val responseMap = result.data as? Map<String, Any?> ?: emptyMap()
            val isValid = responseMap["valid"] as? Boolean ?: false

            if (isValid) {
                val resSessionId = responseMap["sessionId"] as? String ?: sessionId
                val resCourseCode = responseMap["courseCode"] as? String ?: courseCode
                val attempt = (responseMap["attempt"] as? Number)?.toInt() ?: 1
                val retryRemaining = (responseMap["retryRemaining"] as? Number)?.toInt() ?: 0

                QrValidationResult.Success(
                    sessionId = resSessionId,
                    courseCode = resCourseCode,
                    attempt = attempt,
                    retryRemaining = retryRemaining
                )
            } else {
                QrValidationResult.Error("Server returned invalid response")
            }
        } catch (e: Exception) {
            mapFunctionsException(e)
        }
    }

    /**
     * Record an eligible capture/verification problem (or GPS location violation) on the active claim.
     */
    suspend fun recordAttemptFailure(
        sessionId: String,
        courseCode: String,
        failureReason: String
    ): Result<Map<String, Any?>> {
        return try {
            val data = hashMapOf(
                "sessionId" to sessionId.trim(),
                "courseCode" to courseCode.trim(),
                "failureReason" to failureReason.trim()
            )
            val callable = functions.getHttpsCallable("recordVerificationFailure")
            val result = callable.call(data).await()
            @Suppress("UNCHECKED_CAST")
            val resMap = result.data as? Map<String, Any?> ?: emptyMap()
            Result.success(resMap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
