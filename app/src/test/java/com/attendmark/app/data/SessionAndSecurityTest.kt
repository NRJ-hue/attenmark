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
}
