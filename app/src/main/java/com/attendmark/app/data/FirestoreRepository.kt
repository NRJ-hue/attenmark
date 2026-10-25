package com.attendmark.app.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val recordsCollection = db.collection("attendance_records")
    private val sessionsCollection = db.collection("sessions")
    private val attendanceSessionsCollection = db.collection("attendance_sessions")
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
        val courseId = coursesCollection.document().id
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

    /**
     * Starts a new attendance session via backend API (or Cloud Functions in emulator).
     * Enforces authoritative server-side role check, course ownership, and geofence coordinates.
     */
    fun startSession(
        courseCode: String, 
        latitude: Double, 
        longitude: Double, 
        facultyEmail: String, 
        durationMinutes: Int,
        onSuccess: (Long) -> Unit
    ) {
        val payload = hashMapOf<String, Any?>(
            "courseCode" to courseCode,
            "latitude" to latitude,
            "longitude" to longitude,
            "durationMinutes" to durationMinutes
        )

        CoroutineScope(Dispatchers.Main).launch {
            val backendResult = BackendApiClient.post("startAttendanceSession", payload)
            if (backendResult.isSuccess) {
                val resData = backendResult.getOrNull()
                val expiresAt = (resData?.get("expiresAt") as? Number)?.toLong()
                    ?: (System.currentTimeMillis() + durationMinutes * 60 * 1000L)
                onSuccess(expiresAt)
                return@launch
            }

            if (FirebaseEmulatorConfig.isEmulatorEnabled) {
                FirebaseFunctions.getInstance()
                    .getHttpsCallable("startAttendanceSession")
                    .call(payload)
                    .addOnSuccessListener { result ->
                        val resData = result.data as? Map<*, *>
                        val expiresAt = (resData?.get("expiresAt") as? Number)?.toLong()
                            ?: (System.currentTimeMillis() + durationMinutes * 60 * 1000L)
                        onSuccess(expiresAt)
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirestoreRepository", "Failed to start attendance session via Cloud Function", e)
                    }
            } else {
                Log.e("FirestoreRepository", "Failed to start attendance session via backend API", backendResult.exceptionOrNull())
            }
        }
    }

    /**
     * Stops an active attendance session via backend API (or Cloud Functions in emulator).
     */
    fun stopSession(courseCode: String) {
        val payload = hashMapOf<String, Any?>("courseCode" to courseCode)
        CoroutineScope(Dispatchers.Main).launch {
            val backendResult = BackendApiClient.post("stopAttendanceSession", payload)
            if (backendResult.isSuccess) {
                return@launch
            }

            if (FirebaseEmulatorConfig.isEmulatorEnabled) {
                FirebaseFunctions.getInstance()
                    .getHttpsCallable("stopAttendanceSession")
                    .call(payload)
                    .addOnFailureListener { e ->
                        Log.e("FirestoreRepository", "Failed to stop attendance session via Cloud Function", e)
                    }
            } else {
                Log.e("FirestoreRepository", "Failed to stop attendance session via backend API", backendResult.exceptionOrNull())
            }
        }
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

    suspend fun getSessionById(sessionId: String): Map<String, Any>? {
        return try {
            val doc = attendanceSessionsCollection.document(sessionId).get().await()
            if (doc.exists()) doc.data else null
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error getting session by ID", e)
            null
        }
    }

    /**
     * Submit an attendance record to Firestore.
     * Uses atomic document ID `${sessionId}_${studentUid}` when available to prevent duplicate submissions.
     */
    fun submitRecord(record: AttendanceRecord, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val docId = if (record.sessionId.isNotBlank() && record.studentUid.isNotBlank()) {
            "${record.sessionId}_${record.studentUid}"
        } else {
            record.id
        }

        val recordMap = hashMapOf(
            "id" to docId,
            "sessionId" to record.sessionId,
            "studentUid" to record.studentUid,
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
            "rejectionReason" to record.rejectionReason,
            "selfieBase64" to record.selfieBase64,
            "classroomPhotoBase64" to record.classroomPhotoBase64
        )

        recordsCollection.document(docId)
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

    /**
     * Submits an unverified manual attendance request to manual_attendance_requests collection.
     * Separated from verified attendance_records so manual requests cannot bypass QR claim verification
     * or self-assign APPROVED status.
     */
    fun submitManualRequest(record: AttendanceRecord, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val docId = if (record.sessionId.isNotBlank() && record.studentUid.isNotBlank()) {
            "${record.sessionId}_${record.studentUid}"
        } else {
            record.id
        }

        val requestMap = hashMapOf(
            "id" to docId,
            "sessionId" to record.sessionId,
            "studentUid" to record.studentUid,
            "studentName" to record.studentName,
            "studentEmail" to record.studentEmail,
            "courseCode" to record.courseCode,
            "status" to "REVIEW",
            "matchResult" to "MANUAL_REQUEST",
            "isQrVerified" to false,
            "overallScore" to 0,
            "timestamp" to record.timestamp,
            "rejectionReason" to record.rejectionReason
        )

        db.collection("manual_attendance_requests").document(docId)
            .set(requestMap)
            .addOnSuccessListener {
                Log.d("FirestoreRepository", "Manual request successfully written!")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.w("FirestoreRepository", "Error writing manual request", e)
                onFailure(e)
            }
    }

    suspend fun getRecordById(id: String): AttendanceRecord? {
        return try {
            val doc = recordsCollection.document(id).get().await()
            if (doc.exists()) {
                val rawStatus = doc.getString("status") ?: "REVIEW"
                val status = try {
                    AttendanceStatus.valueOf(rawStatus)
                } catch (e: Exception) {
                    AttendanceStatus.REVIEW
                }

                AttendanceRecord(
                    id = doc.id,
                    sessionId = doc.getString("sessionId") ?: "",
                    studentUid = doc.getString("studentUid") ?: "",
                    studentName = doc.getString("studentName") ?: "",
                    studentEmail = doc.getString("studentEmail") ?: "",
                    courseCode = doc.getString("courseCode") ?: "",
                    faceMatchScore = doc.getDouble("faceMatchScore")?.toFloat() ?: 0f,
                    matchPercentage = doc.getLong("matchPercentage")?.toInt() ?: 0,
                    matchResult = doc.getString("matchResult") ?: "",
                    livenessChallenge = doc.getString("livenessChallenge") ?: "",
                    livenessPassed = doc.getBoolean("livenessPassed") ?: false,
                    status = status,
                    overallScore = doc.getLong("overallScore")?.toInt() ?: 0,
                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                    rejectionReason = doc.getString("rejectionReason"),
                    selfieBase64 = doc.getString("selfieBase64") ?: "",
                    classroomPhotoBase64 = doc.getString("classroomPhotoBase64") ?: ""
                )
            } else null
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error fetching record", e)
            null
        }
    }

    fun updateRecordStatus(recordId: String, newStatus: AttendanceStatus, reason: String? = null) {
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
                        val rawStatus = doc.getString("status") ?: "REVIEW"
                        val status = try {
                            AttendanceStatus.valueOf(rawStatus)
                        } catch (ex: Exception) {
                            AttendanceStatus.REVIEW
                        }

                        AttendanceRecord(
                            id = doc.id,
                            sessionId = doc.getString("sessionId") ?: "",
                            studentUid = doc.getString("studentUid") ?: "",
                            studentName = doc.getString("studentName") ?: "",
                            studentEmail = doc.getString("studentEmail") ?: "",
                            courseCode = doc.getString("courseCode") ?: "",
                            faceMatchScore = doc.getDouble("faceMatchScore")?.toFloat() ?: 0f,
                            matchPercentage = doc.getLong("matchPercentage")?.toInt() ?: 0,
                            matchResult = doc.getString("matchResult") ?: "",
                            livenessChallenge = doc.getString("livenessChallenge") ?: "",
                            livenessPassed = doc.getBoolean("livenessPassed") ?: false,
                            status = status,
                            overallScore = doc.getLong("overallScore")?.toInt() ?: 0,
                            timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                            rejectionReason = doc.getString("rejectionReason"),
                            selfieBase64 = doc.getString("selfieBase64") ?: "",
                            classroomPhotoBase64 = doc.getString("classroomPhotoBase64") ?: ""
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
