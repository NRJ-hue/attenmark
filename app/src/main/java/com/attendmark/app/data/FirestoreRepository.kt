package com.attendmark.app.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val recordsCollection = db.collection("attendance_records")
    private val sessionsCollection = db.collection("sessions")
    private val coursesCollection = db.collection("courses")

    // ---- Course Management ----

    fun createCourse(
        name: String,
        facultyEmail: String,
        onSuccess: (Course) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        // Generate a 6-char alphanumeric code
        val classCode = (1..6).map { ('A'..'Z').random() }.joinToString("")
        val courseId = db.collection("courses").document().id
        val course = Course(
            id = courseId,
            name = name,
            classCode = classCode,
            facultyEmail = facultyEmail,
            enrolledStudents = emptyList()
        )
        val courseMap = hashMapOf(
            "name" to course.name,
            "classCode" to course.classCode,
            "facultyEmail" to course.facultyEmail,
            "enrolledStudents" to course.enrolledStudents
        )
        coursesCollection.document(courseId).set(courseMap)
            .addOnSuccessListener { onSuccess(course) }
            .addOnFailureListener { e -> onFailure(e) }
    }

    fun joinCourse(
        classCode: String,
        studentEmail: String,
        onSuccess: (Course) -> Unit,
        onFailure: (String) -> Unit
    ) {
        coursesCollection.whereEqualTo("classCode", classCode).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    onFailure("No course found with this code. Check and try again.")
                    return@addOnSuccessListener
                }
                val doc = snapshot.documents.first()
                val courseId = doc.id
                @Suppress("UNCHECKED_CAST")
                val enrolled = (doc.get("enrolledStudents") as? List<String> ?: emptyList()).toMutableList()
                if (!enrolled.contains(studentEmail)) enrolled.add(studentEmail)
                coursesCollection.document(courseId).update("enrolledStudents", enrolled)
                    .addOnSuccessListener {
                        val course = Course(
                            id = courseId,
                            name = doc.getString("name") ?: "",
                            classCode = classCode,
                            facultyEmail = doc.getString("facultyEmail") ?: "",
                            enrolledStudents = enrolled
                        )
                        onSuccess(course)
                    }
                    .addOnFailureListener { e -> onFailure(e.message ?: "Failed to join.") }
            }
            .addOnFailureListener { e -> onFailure(e.message ?: "Failed to find course.") }
    }

    fun getCoursesForFaculty(
        facultyEmail: String,
        onResult: (List<Course>) -> Unit
    ) {
        coursesCollection.whereEqualTo("facultyEmail", facultyEmail)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) { onResult(emptyList()); return@addSnapshotListener }
                val courses = snapshot.documents.mapNotNull { doc ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        Course(
                            id = doc.id,
                            name = doc.getString("name") ?: "",
                            classCode = doc.getString("classCode") ?: "",
                            facultyEmail = doc.getString("facultyEmail") ?: "",
                            enrolledStudents = doc.get("enrolledStudents") as? List<String> ?: emptyList()
                        )
                    } catch (ex: Exception) { null }
                }
                onResult(courses)
            }
    }

    fun getCoursesForStudent(
        studentEmail: String,
        onResult: (List<Course>) -> Unit
    ) {
        coursesCollection.whereArrayContains("enrolledStudents", studentEmail)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) { onResult(emptyList()); return@addSnapshotListener }
                val courses = snapshot.documents.mapNotNull { doc ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        Course(
                            id = doc.id,
                            name = doc.getString("name") ?: "",
                            classCode = doc.getString("classCode") ?: "",
                            facultyEmail = doc.getString("facultyEmail") ?: "",
                            enrolledStudents = doc.get("enrolledStudents") as? List<String> ?: emptyList()
                        )
                    } catch (ex: Exception) { null }
                }
                onResult(courses)
            }
    }

    suspend fun hasActiveCourseSession(classCode: String): Boolean {
        return try {
            val doc = sessionsCollection.document(classCode).get().await()
            doc.exists() && doc.getBoolean("isActive") == true &&
                System.currentTimeMillis() < (doc.getLong("expiresAt") ?: 0L)
        } catch (e: Exception) { false }
    }

    // ---- Session Management ----

    fun startSession(
        courseCode: String, 
        latitude: Double, 
        longitude: Double, 
        facultyEmail: String, 
        durationMinutes: Int,
        onSuccess: (Long) -> Unit
    ) {
        val expiresAt = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        val sessionData = hashMapOf(
            "facultyEmail" to facultyEmail,
            "latitude" to latitude,
            "longitude" to longitude,
            "isActive" to true,
            "expiresAt" to expiresAt
        )
        sessionsCollection.document(courseCode).set(sessionData)
            .addOnSuccessListener { onSuccess(expiresAt) }
    }

    fun stopSession(courseCode: String) {
        sessionsCollection.document(courseCode).update("isActive", false)
    }

    suspend fun getActiveSession(courseCode: String): Map<String, Any>? {
        return try {
            val doc = sessionsCollection.document(courseCode).get().await()
            if (doc.exists() && doc.getBoolean("isActive") == true) {
                val expiresAt = doc.getLong("expiresAt") ?: 0L
                if (System.currentTimeMillis() < expiresAt) {
                    doc.data
                } else {
                    // Session expired lazily
                    null
                }
            } else null
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error getting session", e)
            null
        }
    }

    /**
     * Submit an attendance record to Firestore
     */
    fun submitRecord(record: AttendanceRecord, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val recordMap = hashMapOf(
            "studentName" to record.studentName,
            "studentEmail" to record.studentEmail,
            "courseCode" to record.courseCode,
            "faceMatchScore" to record.faceMatchScore,
            "matchPercentage" to record.matchPercentage,
            "matchResult" to record.matchResult,
            "livenessChallenge" to record.livenessChallenge,
            "livenessPassed" to record.livenessPassed,
            "status" to record.status.name,
            "overallScore" to record.overallScore,
            "timestamp" to record.timestamp,
            "rejectionReason" to record.rejectionReason
        )

        recordsCollection.document(record.id)
            .set(recordMap)
            .addOnSuccessListener {
                Log.d("FirestoreRepository", "Record successfully written!")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.w("FirestoreRepository", "Error writing record", e)
                onFailure(e)
            }
    }

    suspend fun getRecordById(id: String): AttendanceRecord? {
        return try {
            val doc = recordsCollection.document(id).get().await()
            if (doc.exists()) {
                AttendanceRecord(
                    id = doc.id,
                    studentName = doc.getString("studentName") ?: "",
                    studentEmail = doc.getString("studentEmail") ?: "",
                    courseCode = doc.getString("courseCode") ?: "",
                    faceMatchScore = doc.getDouble("faceMatchScore")?.toFloat() ?: 0f,
                    matchPercentage = doc.getLong("matchPercentage")?.toInt() ?: 0,
                    matchResult = doc.getString("matchResult") ?: "",
                    livenessChallenge = doc.getString("livenessChallenge") ?: "",
                    livenessPassed = doc.getBoolean("livenessPassed") ?: false,
                    status = AttendanceStatus.valueOf(doc.getString("status") ?: "PENDING"),
                    overallScore = doc.getLong("overallScore")?.toInt() ?: 0,
                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                    rejectionReason = doc.getString("rejectionReason")
                )
            } else null
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error fetching record", e)
            null
        }
    }

    fun updateRecordStatus(recordId: String, newStatus: com.attendmark.app.data.AttendanceStatus, reason: String? = null) {
        val updates = mutableMapOf<String, Any>("status" to newStatus.name)
        if (reason != null) updates["rejectionReason"] = reason
        
        recordsCollection.document(recordId)
            .update(updates)
            .addOnSuccessListener {
                Log.d("FirestoreRepository", "Record status updated successfully!")
            }
            .addOnFailureListener { e ->
                Log.w("FirestoreRepository", "Error updating record", e)
            }
    }

    /**
     * Listen to attendance records in real-time, filtered by courseCode
     */
    fun getRecordsFlow(courseCode: String = ""): Flow<List<AttendanceRecord>> = callbackFlow {
        val query = if (courseCode.isNotBlank()) {
            recordsCollection
                .whereEqualTo("courseCode", courseCode)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
        } else {
            recordsCollection
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
        }
        val listenerRegistration = query.addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("FirestoreRepository", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val records = snapshot.documents.mapNotNull { doc ->
                        try {
                            AttendanceRecord(
                                id = doc.id,
                                studentName = doc.getString("studentName") ?: "",
                                studentEmail = doc.getString("studentEmail") ?: "",
                                courseCode = doc.getString("courseCode") ?: "",
                                faceMatchScore = doc.getDouble("faceMatchScore")?.toFloat() ?: 0f,
                                matchPercentage = doc.getLong("matchPercentage")?.toInt() ?: 0,
                                matchResult = doc.getString("matchResult") ?: "",
                                livenessChallenge = doc.getString("livenessChallenge") ?: "",
                                livenessPassed = doc.getBoolean("livenessPassed") ?: false,
                                status = AttendanceStatus.valueOf(doc.getString("status") ?: "REVIEW"),
                                overallScore = doc.getLong("overallScore")?.toInt() ?: 0,
                                timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                rejectionReason = doc.getString("rejectionReason")
                            )
                        } catch (ex: Exception) {
                            Log.e("FirestoreRepository", "Error parsing record", ex)
                            null
                        }
                    }
                    trySend(records)
                }
            }

        awaitClose {
            listenerRegistration.remove()
        }
    }
}
