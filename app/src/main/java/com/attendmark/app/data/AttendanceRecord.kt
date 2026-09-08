package com.attendmark.app.data

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a completed attendance submission record stored locally on faculty's device.
 */
data class AttendanceRecord(
    val id: String = UUID.randomUUID().toString(),
    val studentName: String,
    val studentEmail: String,
    val courseCode: String,
    val timestamp: Long = System.currentTimeMillis(),
    val faceMatchScore: Float,       // 0.0 to 1.0
    val matchPercentage: Int,         // 0 to 100
    val matchResult: String,          // STRONG_MATCH, LIKELY_MATCH, WEAK_MATCH, NO_MATCH
    val livenessChallenge: String,    // e.g., "BLINK"
    val livenessPassed: Boolean,
    val status: AttendanceStatus,
    val overallScore: Int = 0,        // 0 to 100 Trust Score
    val selfieBase64: String = "",    // for faculty review (optional, cleared after review)
    val classroomPhotoBase64: String = "", // BeReal classroom photo
    val rejectionReason: String? = null // Optional reason if faculty rejects
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

enum class AttendanceStatus(val label: String, val description: String) {
    APPROVED("Approved", "Identity verified successfully"),
    REVIEW("Under Review", "Low confidence — faculty review needed"),
    REJECTED("Rejected", "Identity could not be verified"),
}

/**
 * In-memory store for attendance records (MVP — no backend, no database).
 * In production, this would sync to a server.
 */
object AttendanceStore {
    private val records = mutableListOf<AttendanceRecord>()

    fun addRecord(record: AttendanceRecord) {
        records.add(0, record) // newest first
    }

    fun getAllRecords(): List<AttendanceRecord> = records.toList()

    fun getStudentRecords(email: String): List<AttendanceRecord> =
        records.filter { it.studentEmail == email }

    fun getFacultyRecords(): List<AttendanceRecord> = records.toList()

    fun getRecordById(id: String): AttendanceRecord? = records.find { it.id == id }

    fun updateRecord(updated: AttendanceRecord) {
        val index = records.indexOfFirst { it.id == updated.id }
        if (index >= 0) records[index] = updated
    }

    fun clear() = records.clear()
}
