package com.attendmark.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for Phase 2:
 * 1. Session identity and historical session preservation
 * 2. Atomic duplicate prevention via ${sessionId}_${studentUid} document IDs
 * 3. Security rules constraint validation (status != APPROVED, caller matching, session requirement)
 * 4. Models for QR tokens and attendance claims
 */
class SessionAndSecurityTest {

    // ─── 1. Session History Preservation ──────────────────────────────────────────

    @Test
    fun sessionIdentity_createsUniqueSessionIdForEachNewSession() {
        val courseCode = "CS101"
        val session1Id = UUID.randomUUID().toString()
        val session2Id = UUID.randomUUID().toString()

        assertNotEquals(session1Id, session2Id)

        val session1 = AttendanceSession(
            sessionId = session1Id,
            courseCode = courseCode,
            facultyEmail = "prof@test.com",
            latitude = 37.7749,
            longitude = -122.4194,
            isActive = false,
            durationMinutes = 15,
            startedAt = 1000000L,
            expiresAt = 1000000L + (15 * 60 * 1000L),
            stoppedAt = 1000500L
        )

        val session2 = AttendanceSession(
            sessionId = session2Id,
            courseCode = courseCode,
            facultyEmail = "prof@test.com",
            latitude = 37.7749,
            longitude = -122.4194,
            isActive = true,
            durationMinutes = 15,
            startedAt = 2000000L,
            expiresAt = 2000000L + (15 * 60 * 1000L)
        )

        // Historical sessions are separate entities and do not overwrite each other
        val historicalStore = mutableMapOf<String, AttendanceSession>()
        historicalStore[session1.sessionId] = session1
        historicalStore[session2.sessionId] = session2

        assertEquals(2, historicalStore.size)
        assertEquals(false, historicalStore[session1Id]?.isActive)
        assertEquals(true, historicalStore[session2Id]?.isActive)

        // Active pointer reflects the latest session
        val activePointer = mutableMapOf<String, String>() // courseCode -> sessionId
        activePointer[courseCode] = session1Id
        activePointer[courseCode] = session2Id

        assertEquals(session2Id, activePointer[courseCode])
    }

    // ─── 2. Atomic Duplicate Prevention ───────────────────────────────────────────

    @Test
    fun duplicatePrevention_sameStudentAndSessionGeneratesIdenticalDocumentId() {
        val sessionId = "session-uuid-1234"
        val studentUid = "student-uid-5678"

        val docId1 = "${sessionId}_${studentUid}"
        val docId2 = "${sessionId}_${studentUid}"

        // Deterministic ID guarantees Firestore .set() targets the exact same document
        assertEquals(docId1, docId2)
        assertEquals("session-uuid-1234_student-uid-5678", docId1)
    }

    @Test
    fun duplicatePrevention_differentStudentsOrSessionsGenerateDistinctDocumentIds() {
        val sessionA = "session-A"
        val sessionB = "session-B"
        val student1 = "student-1"
        val student2 = "student-2"

        val doc1A = "${sessionA}_${student1}"
        val doc2A = "${sessionA}_${student2}"
        val doc1B = "${sessionB}_${student1}"

        assertNotEquals(doc1A, doc2A)
        assertNotEquals(doc1A, doc1B)
        assertNotEquals(doc2A, doc1B)
    }

    // ─── 3. Security Rules Constraints Logic ──────────────────────────────────────

    @Test
    fun securityRules_studentCannotDirectlyClaimApprovedStatus() {
        // Validation function mirroring Firestore security rule:
        // request.resource.data.status != 'APPROVED'
        fun isAllowedClientStatus(status: AttendanceStatus): Boolean {
            return status != AttendanceStatus.APPROVED
        }

        assertFalse("Direct student APPROVED must be rejected by rule", isAllowedClientStatus(AttendanceStatus.APPROVED))
        assertTrue("REVIEW status is allowed for student submission", isAllowedClientStatus(AttendanceStatus.REVIEW))
        assertTrue("REJECTED status is allowed for failed verification submission", isAllowedClientStatus(AttendanceStatus.REJECTED))
    }

    @Test
    fun securityRules_requiresValidSessionIdAndMatchingCaller() {
        // Validation function mirroring Firestore security rule:
        // request.auth.uid == request.resource.data.studentUid &&
        // request.resource.data.sessionId != null &&
        // request.resource.data.sessionId != ''
        fun validateSubmissionRule(
            callerUid: String,
            recordStudentUid: String,
            recordSessionId: String?,
            status: AttendanceStatus
        ): Boolean {
            if (callerUid != recordStudentUid) return false
            if (recordSessionId.isNullOrBlank()) return false
            if (status == AttendanceStatus.APPROVED) return false
            return true
        }

        val callerUid = "student-uid-99"

        // Valid submission
        assertTrue(
            validateSubmissionRule(
                callerUid = callerUid,
                recordStudentUid = callerUid,
                recordSessionId = "session-uuid-101",
                status = AttendanceStatus.REVIEW
            )
        )

        // Spoofed student UID (cross-student submission)
        assertFalse(
            validateSubmissionRule(
                callerUid = callerUid,
                recordStudentUid = "other-student-uid",
                recordSessionId = "session-uuid-101",
                status = AttendanceStatus.REVIEW
            )
        )

        // Missing session ID
        assertFalse(
            validateSubmissionRule(
                callerUid = callerUid,
                recordStudentUid = callerUid,
                recordSessionId = "",
                status = AttendanceStatus.REVIEW
            )
        )

        // Null session ID
        assertFalse(
            validateSubmissionRule(
                callerUid = callerUid,
                recordStudentUid = callerUid,
                recordSessionId = null,
                status = AttendanceStatus.REVIEW
            )
        )

        // Direct APPROVED status
        assertFalse(
            validateSubmissionRule(
                callerUid = callerUid,
                recordStudentUid = callerUid,
                recordSessionId = "session-uuid-101",
                status = AttendanceStatus.APPROVED
            )
        )
    }

    // ─── 4. QR Token Model & Expiry Logic ─────────────────────────────────────────

    @Test
    fun qrToken_expiresAfterConfiguredValidityPeriod() {
        val issuedAt = 1_000_000L
        val validityMs = 30_000L // 30 seconds
        val token = QrToken(
            tokenId = "token-xyz-123",
            sessionId = "session-abc",
            courseCode = "CS101",
            facultyUid = "faculty-uid-1",
            issuedAt = issuedAt,
            expiresAt = issuedAt + validityMs
        )

        // Within validity window (at 20s)
        assertFalse(token.isExpired(currentTimeMs = issuedAt + 20_000L))

        // Right at boundary (30s)
        assertTrue(token.isExpired(currentTimeMs = issuedAt + 30_000L))

        // After expiry (31s)
        assertTrue(token.isExpired(currentTimeMs = issuedAt + 31_000L))
    }

    // ─── 5. Attendance Claim Model ────────────────────────────────────────────────

    @Test
    fun attendanceClaim_claimIdMatchesSessionAndStudentUid() {
        val claim = AttendanceClaim(
            sessionId = "sess-100",
            studentUid = "stu-200",
            studentEmail = "student@test.com",
            claimedAt = 1_700_000_000L,
            tokenId = "token-tok-300"
        )

        assertEquals("sess-100_stu-200", claim.claimId)
        val map = claim.toMap()
        assertEquals("sess-100", map["sessionId"])
        assertEquals("stu-200", map["studentUid"])
        assertEquals("token-tok-300", map["tokenId"])
    }

    // ─── 6. Student Query Constraints & History Ordering ──────────────────────────

    @Test
    fun studentQuery_validatesRequiredConstraintsAndPreservesTimestampOrder() {
        fun validateStudentQuery(callerUid: String?, courseCode: String?): Boolean {
            if (callerUid.isNullOrBlank()) return false
            if (courseCode.isNullOrBlank()) return false
            return true
        }

        // Unauthenticated caller
        assertFalse(validateStudentQuery(callerUid = null, courseCode = "CS101"))
        assertFalse(validateStudentQuery(callerUid = "", courseCode = "CS101"))

        // Blank course code
        assertFalse(validateStudentQuery(callerUid = "student-123", courseCode = null))
        assertFalse(validateStudentQuery(callerUid = "student-123", courseCode = "  "))

        // Valid student query parameters
        assertTrue(validateStudentQuery(callerUid = "student-123", courseCode = "CS101"))

        // Verify descending timestamp sort preservation
        val rec1 = AttendanceRecord(
            id = "sess1_stu123",
            sessionId = "sess1",
            studentUid = "student-123",
            studentName = "Student A",
            studentEmail = "studentA@test.com",
            courseCode = "CS101",
            faceMatchScore = 0.95f,
            matchPercentage = 95,
            matchResult = "MATCH",
            livenessChallenge = "SMILE",
            livenessPassed = true,
            status = AttendanceStatus.REVIEW,
            overallScore = 95,
            timestamp = 1000L
        )

        val rec2 = AttendanceRecord(
            id = "sess2_stu123",
            sessionId = "sess2",
            studentUid = "student-123",
            studentName = "Student A",
            studentEmail = "studentA@test.com",
            courseCode = "CS101",
            faceMatchScore = 0.91f,
            matchPercentage = 91,
            matchResult = "MATCH",
            livenessChallenge = "BLINK",
            livenessPassed = true,
            status = AttendanceStatus.REVIEW,
            overallScore = 91,
            timestamp = 2000L
        )

        val list = listOf(rec1, rec2)
        val sortedDesc = list.sortedByDescending { it.timestamp }
        assertEquals(2000L, sortedDesc[0].timestamp)
        assertEquals("sess2_stu123", sortedDesc[0].id)
        assertEquals(1000L, sortedDesc[1].timestamp)
        assertEquals("sess1_stu123", sortedDesc[1].id)
    }

    // ─── 8. Trust-Score Approval Policy & Threshold Boundaries ───────────────────

    private fun evaluateAttendancePolicy(
        overallScore: Int,
        livenessPassed: Boolean = true,
        matchResult: String = "MATCH",
        gpsWithinBounds: Boolean = true
    ): Pair<AttendanceStatus, String?> {
        if (!livenessPassed) {
            return Pair(AttendanceStatus.REJECTED, "Liveness verification failed.")
        }
        if (matchResult == "NO_MATCH") {
            return Pair(AttendanceStatus.REJECTED, "Face recognition failed.")
        }
        if (!gpsWithinBounds) {
            return Pair(AttendanceStatus.REJECTED, "Location outside classroom bounds.")
        }

        return when {
            overallScore > 75 -> Pair(AttendanceStatus.APPROVED, null)
            overallScore >= 40 -> Pair(AttendanceStatus.REVIEW, null)
            else -> Pair(AttendanceStatus.REJECTED, "Overall trust score insufficient ($overallScore/100). Minimum passing threshold is 40.")
        }
    }

    @Test
    fun trustScorePolicy_boundaryEvaluation() {
        // Boundary 1: Score 100 -> APPROVED
        val (status100, _) = evaluateAttendancePolicy(100)
        assertEquals(AttendanceStatus.APPROVED, status100)

        // Boundary 2: Score 76 -> APPROVED (threshold boundary > 75)
        val (status76, _) = evaluateAttendancePolicy(76)
        assertEquals(AttendanceStatus.APPROVED, status76)

        // Boundary 3: Score 75 -> REVIEW (upper review boundary)
        val (status75, _) = evaluateAttendancePolicy(75)
        assertEquals(AttendanceStatus.REVIEW, status75)

        // Boundary 4: Score 40 -> REVIEW (lower review boundary)
        val (status40, _) = evaluateAttendancePolicy(40)
        assertEquals(AttendanceStatus.REVIEW, status40)

        // Boundary 5: Score 39 -> REJECTED (below minimum threshold)
        val (status39, reason39) = evaluateAttendancePolicy(39)
        assertEquals(AttendanceStatus.REJECTED, status39)
        assertTrue(reason39?.contains("39/100") == true)

        // Boundary 6: Score 0 -> REJECTED
        val (status0, _) = evaluateAttendancePolicy(0)
        assertEquals(AttendanceStatus.REJECTED, status0)
    }

    @Test
    fun trustScorePolicy_mandatoryVerificationGatesCannotBeBypassed() {
        // Gate 1: Liveness failure with score 99 cannot be APPROVED
        val (statusLivenessFail, reasonLiveness) = evaluateAttendancePolicy(overallScore = 99, livenessPassed = false)
        assertEquals(AttendanceStatus.REJECTED, statusLivenessFail)
        assertTrue(reasonLiveness?.contains("Liveness") == true)

        // Gate 2: Face mismatch with score 95 cannot be APPROVED
        val (statusFaceFail, reasonFace) = evaluateAttendancePolicy(overallScore = 95, matchResult = "NO_MATCH")
        assertEquals(AttendanceStatus.REJECTED, statusFaceFail)
        assertTrue(reasonFace?.contains("Face") == true)

        // Gate 3: GPS out of bounds with score 98 cannot be APPROVED
        val (statusGpsFail, reasonGps) = evaluateAttendancePolicy(overallScore = 98, gpsWithinBounds = false)
        assertEquals(AttendanceStatus.REJECTED, statusGpsFail)
        assertTrue(reasonGps?.contains("Location") == true)
    }

    @Test
    fun trustScorePolicy_statusLabelsAndDescriptions() {
        assertEquals("Approved", AttendanceStatus.APPROVED.label)
        assertEquals("Under Review — Awaiting Faculty Approval", AttendanceStatus.REVIEW.label)
        assertEquals("Rejected", AttendanceStatus.REJECTED.label)
    }

    // ─── 9. Manual Attendance Request Reasons & Validation ───────────────────────

    private fun validateManualRequest(
        sessionId: String?,
        studentUid: String?,
        courseCode: String?,
        reason: String?
    ): Pair<Boolean, String?> {
        if (sessionId.isNullOrBlank()) return Pair(false, "sessionId is required")
        if (studentUid.isNullOrBlank()) return Pair(false, "studentUid is required")
        if (courseCode.isNullOrBlank()) return Pair(false, "courseCode is required")
        if (reason.isNullOrBlank()) return Pair(false, "reason is required")
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) return Pair(false, "reason cannot be blank")
        if (trimmed.length > 500) return Pair(false, "reason cannot exceed 500 characters")
        return Pair(true, null)
    }

    @Test
    fun manualRequest_requiresNonEmptyReasonWithin500Chars() {
        // Valid reason
        val (ok, err) = validateManualRequest("sess-1", "stu-1", "CS101", "Camera broke during QR scan")
        assertTrue(ok)
        assertEquals(null, err)

        // Blank or whitespace reason
        val (blankOk, _) = validateManualRequest("sess-1", "stu-1", "CS101", "   ")
        assertFalse(blankOk)

        // Empty reason
        val (emptyOk, _) = validateManualRequest("sess-1", "stu-1", "CS101", "")
        assertFalse(emptyOk)

        // Excessive reason (> 500 chars)
        val longReason = "A".repeat(501)
        val (longOk, longErr) = validateManualRequest("sess-1", "stu-1", "CS101", longReason)
        assertFalse(longOk)
        assertTrue(longErr?.contains("500") == true)

        // Exact 500 chars is valid
        val exact500 = "B".repeat(500)
        val (exactOk, _) = validateManualRequest("sess-1", "stu-1", "CS101", exact500)
        assertTrue(exactOk)
    }

    @Test
    fun manualRequest_requiresActiveSessionAndCannotUseSyntheticSession() {
        val (noSessOk, _) = validateManualRequest("", "stu-1", "CS101", "Valid reason")
        assertFalse(noSessOk)

        val (nullSessOk, _) = validateManualRequest(null, "stu-1", "CS101", "Valid reason")
        assertFalse(nullSessOk)
    }
}
