package com.attendmark.app.data

/**
 * Historical attendance session record stored in attendance_sessions/{sessionId}.
 */
data class AttendanceSession(
    val sessionId: String,
    val courseCode: String,
    val facultyEmail: String,
    val latitude: Double,
    val longitude: Double,
    val isActive: Boolean = true,
    val durationMinutes: Int = 10,
    val startedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = startedAt + (durationMinutes * 60 * 1000L),
    val stoppedAt: Long? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "sessionId" to sessionId,
        "courseCode" to courseCode,
        "facultyEmail" to facultyEmail,
        "latitude" to latitude,
        "longitude" to longitude,
        "isActive" to isActive,
        "durationMinutes" to durationMinutes,
        "startedAt" to startedAt,
        "expiresAt" to expiresAt,
        "stoppedAt" to stoppedAt
    )
}

/**
 * Ephemeral QR token stored in qr_tokens/{tokenId}.
 * Issued strictly by backend Cloud Functions.
 */
data class QrToken(
    val tokenId: String,
    val sessionId: String,
    val courseCode: String,
    val facultyUid: String,
    val issuedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = issuedAt + 30000L // default 30s validity
) {
    fun isExpired(currentTimeMs: Long = System.currentTimeMillis()): Boolean = currentTimeMs >= expiresAt

    fun toMap(): Map<String, Any> = mapOf(
        "tokenId" to tokenId,
        "sessionId" to sessionId,
        "courseCode" to courseCode,
        "facultyUid" to facultyUid,
        "issuedAt" to issuedAt,
        "expiresAt" to expiresAt
    )
}

/**
 * Attendance claim record stored in attendance_claims/{sessionId + "_" + studentUid}.
 * Written atomically by backend transaction to prevent duplicate attendance claims.
 */
data class AttendanceClaim(
    val sessionId: String,
    val studentUid: String,
    val studentEmail: String,
    val claimedAt: Long = System.currentTimeMillis(),
    val tokenId: String
) {
    val claimId: String get() = "${sessionId}_${studentUid}"

    fun toMap(): Map<String, Any> = mapOf(
        "sessionId" to sessionId,
        "studentUid" to studentUid,
        "studentEmail" to studentEmail,
        "claimedAt" to claimedAt,
        "tokenId" to tokenId
    )
}
