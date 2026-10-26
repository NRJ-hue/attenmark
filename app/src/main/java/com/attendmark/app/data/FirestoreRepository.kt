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

sealed class ActiveSessionState {
    object Loading : ActiveSessionState()
    object None : ActiveSessionState()
    data class Active(val sessionData: Map<String, Any>) : ActiveSessionState()
    data class Error(val message: String, val cause: Throwable? = null) : ActiveSessionState()
}

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
                if (doc.getBoolean("isArchived") == true) {
                    onFailure("This course has been archived by the instructor and is not accepting enrollments.")
                    return@addOnSuccessListener
                }
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

    suspend fun leaveCourse(classCode: String): Result<Boolean> {
        val payload = hashMapOf<String, Any?>("courseCode" to classCode)
        val res = BackendApiClient.post("leaveCourse", payload)
        if (res.isSuccess) {
            return Result.success(true)
        }
        return Result.failure(res.exceptionOrNull() ?: Exception("Failed to leave course."))
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
            val isActive = doc.getBoolean("isActive") == true
            val expiresAt = (doc.get("expiresAt") as? Number)?.toLong() ?: 0L
            doc.exists() && isActive && System.currentTimeMillis() < expiresAt
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error checking active session for $classCode", e)
            false
        }
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

    suspend fun getActiveSessionResult(courseCode: String): Result<Map<String, Any>?> {
        return try {
            val doc = sessionsCollection.document(courseCode).get().await()
            if (doc.exists() && doc.getBoolean("isActive") == true) {
                val expiresAt = (doc.get("expiresAt") as? Number)?.toLong() ?: 0L
                if (System.currentTimeMillis() < expiresAt) {
                    Result.success(doc.data)
                } else {
                    // Session expired lazily
                    Result.success(null)
                }
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error getting session for $courseCode", e)
            Result.failure(e)
        }
    }

    suspend fun getActiveSession(courseCode: String): Map<String, Any>? {
        return getActiveSessionResult(courseCode).getOrNull()
    }

    fun getActiveSessionStateFlow(courseCode: String): Flow<ActiveSessionState> = callbackFlow {
        val listener = sessionsCollection.document(courseCode)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreRepository", "Error listening to active session for $courseCode", error)
                    trySend(ActiveSessionState.Error(error.localizedMessage ?: "Firestore error listening to session", error))
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && snapshot.getBoolean("isActive") == true) {
                    val expiresAt = (snapshot.get("expiresAt") as? Number)?.toLong() ?: 0L
                    if (System.currentTimeMillis() < expiresAt) {
                        trySend(ActiveSessionState.Active(snapshot.data ?: emptyMap()))
                    } else {
                        trySend(ActiveSessionState.None)
                    }
                } else {
                    trySend(ActiveSessionState.None)
                }
            }
        awaitClose {
            listener.remove()
        }
    }

    fun getActiveSessionFlow(courseCode: String): Flow<Map<String, Any>?> = callbackFlow {
        val listener = sessionsCollection.document(courseCode)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreRepository", "Error listening to active session for $courseCode", error)
                    trySend(null)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists() && snapshot.getBoolean("isActive") == true) {
                    val expiresAt = (snapshot.get("expiresAt") as? Number)?.toLong() ?: 0L
                    if (System.currentTimeMillis() < expiresAt) {
                        trySend(snapshot.data)
                    } else {
                        trySend(null)
                    }
                } else {
                    trySend(null)
                }
            }
        awaitClose {
            listener.remove()
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
     * Submit an attendance record through the authoritative backend endpoint.
     * The backend validates the student's active QR claim, enforces trust-score thresholds
     * (>75 Approved, 40..75 Review, <40 Rejected), writes via Admin SDK, and marks the claim submitted.
     *
     * In the event of network/backend failure, falls back to direct client write with safe status
     * (downgrading APPROVED to REVIEW to satisfy firestore.rules).
     */
    suspend fun submitRecordViaBackend(record: AttendanceRecord): Result<AttendanceRecord> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val payload = mapOf(
                    "sessionId" to record.sessionId,
                    "courseCode" to record.courseCode,
                    "overallScore" to record.overallScore,
                    "matchPercentage" to record.matchPercentage,
                    "matchResult" to record.matchResult,
                    "livenessChallenge" to record.livenessChallenge,
                    "livenessPassed" to record.livenessPassed,
                    "faceMatchScore" to record.faceMatchScore,
                    "selfieBase64" to record.selfieBase64,
                    "classroomPhotoBase64" to record.classroomPhotoBase64,
                    "studentName" to record.studentName,
                    "studentEmail" to record.studentEmail,
                    "rejectionReason" to record.rejectionReason,
                    "gpsVerificationStatus" to record.gpsVerificationStatus
                )

                val backendResult = BackendApiClient.post("submitAttendanceRecord", payload)
                if (backendResult.isSuccess) {
                    val data = backendResult.getOrNull()
                    val serverStatusStr = (data?.get("status") as? String) ?: record.status.name
                    val serverStatus = try {
                        AttendanceStatus.valueOf(serverStatusStr)
                    } catch (e: Exception) {
                        record.status
                    }
                    val serverRejectionReason = (data?.get("rejectionReason") as? String) ?: record.rejectionReason
                    Result.success(record.copy(status = serverStatus, rejectionReason = serverRejectionReason))
                } else {
                    Log.w("FirestoreRepository", "Backend submit failed, falling back to client write: ${backendResult.exceptionOrNull()?.message}")
                    // Direct client writes cannot be APPROVED (firestore.rules blocks request.resource.data.status == 'APPROVED')
                    // So client write falls back to REVIEW
                    val clientSafeStatus = if (record.status == AttendanceStatus.APPROVED) AttendanceStatus.REVIEW else record.status
                    val clientSafeRecord = record.copy(status = clientSafeStatus)
                    val directResult = submitRecordDirect(clientSafeRecord)
                    if (directResult.isSuccess) {
                        Result.success(clientSafeRecord)
                    } else {
                        Result.failure(backendResult.exceptionOrNull() ?: Exception("Failed to persist attendance record."))
                    }
                }
            } catch (e: Exception) {
                Log.e("FirestoreRepository", "submitRecordViaBackend exception", e)
                Result.failure(e)
            }
        }

    private suspend fun submitRecordDirect(record: AttendanceRecord): Result<Unit> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
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
                    "gpsVerificationStatus" to record.gpsVerificationStatus,
                    "selfieBase64" to record.selfieBase64,
                    "classroomPhotoBase64" to record.classroomPhotoBase64
                )

                recordsCollection.document(docId).set(recordMap).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e("FirestoreRepository", "submitRecordDirect failed", e)
                Result.failure(e)
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
            "gpsVerificationStatus" to record.gpsVerificationStatus,
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
        if (record.sessionId.isBlank() || record.studentUid.isBlank() || record.courseCode.isBlank()) {
            onFailure(IllegalArgumentException("Cannot submit manual request: sessionId, studentUid, and courseCode are strictly required."))
            return
        }

        val reason = record.rejectionReason ?: ""
        if (reason.isBlank()) {
            onFailure(IllegalArgumentException("Cannot submit manual request: a non-empty reason is strictly required."))
            return
        }

        if (reason.length > 600) {
            onFailure(IllegalArgumentException("Cannot submit manual request: reason exceeds maximum length."))
            return
        }

        val docId = "${record.sessionId}_${record.studentUid}"

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
                    gpsVerificationStatus = doc.getString("gpsVerificationStatus"),
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

    private fun parseAttendanceRecord(doc: com.google.firebase.firestore.DocumentSnapshot): AttendanceRecord? {
        return try {
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
                gpsVerificationStatus = doc.getString("gpsVerificationStatus"),
                selfieBase64 = doc.getString("selfieBase64") ?: "",
                classroomPhotoBase64 = doc.getString("classroomPhotoBase64") ?: ""
            )
        } catch (ex: Exception) {
            Log.e("FirestoreRepository", "Error parsing record", ex)
            null
        }
    }

    /**
     * Listen to a student's own attendance records in real-time,
     * constrained by courseCode and the authenticated student's UID.
     * Preserves timestamp descending ordering.
     */
    fun getStudentRecordsFlow(courseCode: String, studentUid: String? = null): Flow<List<AttendanceRecord>> = callbackFlow {
        val effectiveUid = studentUid?.takeIf { it.isNotBlank() }
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

        if (effectiveUid.isNullOrBlank()) {
            Log.w("FirestoreRepository", "Cannot query student attendance records: User is not authenticated.")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val trimmedCourse = courseCode.trim().uppercase()
        if (trimmedCourse.isBlank()) {
            Log.w("FirestoreRepository", "Cannot query student attendance records: Course code is blank.")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val query = recordsCollection
            .whereEqualTo("courseCode", trimmedCourse)
            .whereEqualTo("studentUid", effectiveUid)
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)

        val listenerRegistration = query.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("FirestoreRepository", "Student records listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val records = snapshot.documents.mapNotNull { doc ->
                    parseAttendanceRecord(doc)
                }
                trySend(records)
            }
        }

        awaitClose {
            listenerRegistration.remove()
        }
    }

    /**
     * Listen to attendance records in real-time.
     * - If studentUid is provided: constrains by both courseCode and studentUid.
     * - If studentUid is null (faculty caller): constrains by courseCode.
     * - In all cases, requires authenticated caller and valid courseCode to prevent unrestricted collection scans.
     */
    fun getRecordsFlow(courseCode: String = "", studentUid: String? = null): Flow<List<AttendanceRecord>> = callbackFlow {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w("FirestoreRepository", "Cannot query attendance records: User is not authenticated.")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val trimmedCourse = courseCode.trim().uppercase()
        if (trimmedCourse.isBlank()) {
            Log.w("FirestoreRepository", "Cannot query attendance records: Course code must be specified.")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val query = if (!studentUid.isNullOrBlank()) {
            recordsCollection
                .whereEqualTo("courseCode", trimmedCourse)
                .whereEqualTo("studentUid", studentUid)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
        } else {
            // Faculty query across all records for this course
            recordsCollection
                .whereEqualTo("courseCode", trimmedCourse)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
        }

        val listenerRegistration = query.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("FirestoreRepository", "Records listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val records = snapshot.documents.mapNotNull { doc ->
                    parseAttendanceRecord(doc)
                }
                trySend(records)
            }
        }

        awaitClose {
            listenerRegistration.remove()
        }
    }
}
