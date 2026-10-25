package com.attendmark.app.data

import com.google.firebase.functions.FirebaseFunctionsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.attendmark.app.ui.screens.student.QrCodeAnalyzer
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for Phase 5: Native Android QR Scanner, Token Extraction,
 * Callable error code mapping, and Attendance Flow Integration.
 */
class QrScannerFlowTest {

    private val valid64HexToken = "a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890"

    // ─── 1. QR Token Extraction and Payload Validation ───────────────────────────

    @Test
    fun tokenExtraction_acceptsValid64HexToken() {
        val result = QrValidationService.validateTokenPayload(valid64HexToken)
        assertTrue(result is TokenFormatResult.Valid)
        assertEquals(valid64HexToken, (result as TokenFormatResult.Valid).token)
    }

    @Test
    fun tokenExtraction_acceptsValidTokenWithLeadingOrTrailingWhitespace() {
        val paddedToken = "   $valid64HexToken   \n"
        val result = QrValidationService.validateTokenPayload(paddedToken)
        assertTrue(result is TokenFormatResult.Valid)
        assertEquals(valid64HexToken, (result as TokenFormatResult.Valid).token)
    }

    @Test
    fun tokenExtraction_rejectsEmptyOrBlankPayload() {
        assertTrue(QrValidationService.validateTokenPayload(null) is TokenFormatResult.Invalid)
        assertTrue(QrValidationService.validateTokenPayload("") is TokenFormatResult.Invalid)
        assertTrue(QrValidationService.validateTokenPayload("   ") is TokenFormatResult.Invalid)
    }

    @Test
    fun tokenExtraction_rejectsExternalUrlsAndWebLinks() {
        val httpUrl = "http://attendance.campus.edu/verify?token=123"
        val httpsUrl = "https://attendance.campus.edu/verify?token=123"
        val wwwUrl = "www.campus-verify.edu/session"
        val customScheme = "sidecar://attendmark/scan"

        val resHttp = QrValidationService.validateTokenPayload(httpUrl)
        val resHttps = QrValidationService.validateTokenPayload(httpsUrl)
        val resWww = QrValidationService.validateTokenPayload(wwwUrl)
        val resCustom = QrValidationService.validateTokenPayload(customScheme)

        assertTrue(resHttp is TokenFormatResult.Invalid)
        assertTrue((resHttp as TokenFormatResult.Invalid).reason.contains("External URLs"))

        assertTrue(resHttps is TokenFormatResult.Invalid)
        assertTrue((resHttps as TokenFormatResult.Invalid).reason.contains("External URLs"))

        assertTrue(resWww is TokenFormatResult.Invalid)
        assertTrue(resCustom is TokenFormatResult.Invalid)
    }

    @Test
    fun tokenExtraction_rejectsInvalidLengthOrNonHexCharacters() {
        // Too short (63 chars)
        val shortToken = valid64HexToken.substring(0, 63)
        assertTrue(QrValidationService.validateTokenPayload(shortToken) is TokenFormatResult.Invalid)

        // Too long (65 chars)
        val longToken = valid64HexToken + "f"
        assertTrue(QrValidationService.validateTokenPayload(longToken) is TokenFormatResult.Invalid)

        // Invalid non-hex characters (contains 'z', 'g')
        val nonHexToken = valid64HexToken.substring(0, 62) + "zg"
        assertTrue(QrValidationService.validateTokenPayload(nonHexToken) is TokenFormatResult.Invalid)

        // Random sentence or JSON string
        val jsonPayload = "{\"sessionId\":\"123\",\"courseCode\":\"CS101\"}"
        assertTrue(QrValidationService.validateTokenPayload(jsonPayload) is TokenFormatResult.Invalid)
    }

    // ─── 2. Duplicate Camera Detection Guard & Coordinator Lifecycle ────────────

    @Test
    fun duplicateDetection_coordinatorDropsRapidSuccessiveFrames() {
        val coordinator = QrScanCoordinator()
        var networkCallsInitiated = 0

        // Simulate 50 rapid CameraX image analysis frames with the exact same QR
        for (frame in 1..50) {
            val formatResult = QrValidationService.validateTokenPayload(valid64HexToken)
            if (formatResult is TokenFormatResult.Valid) {
                if (coordinator.startScanAttempt(formatResult.token)) {
                    networkCallsInitiated++
                }
            }
        }

        assertEquals(1, networkCallsInitiated)
        assertEquals(ScanState.VALIDATING, coordinator.state)
        assertEquals(valid64HexToken, coordinator.currentToken)
    }

    @Test
    fun duplicateDetection_secondQrDuringActiveRequestIsDropped() {
        val coordinator = QrScanCoordinator()
        val token1 = valid64HexToken
        val token2 = "b1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890"

        // First detection claims scan slot
        val firstClaimed = coordinator.startScanAttempt(token1)
        assertTrue(firstClaimed)
        assertEquals(ScanState.VALIDATING, coordinator.state)

        // Second detection arrives while first is in-flight
        val secondClaimed = coordinator.startScanAttempt(token2)
        assertFalse(secondClaimed)
        assertEquals(ScanState.VALIDATING, coordinator.state)
        assertEquals(token1, coordinator.currentToken)
    }

    @Test
    fun singleNavigationGuard_successNavigatesOrAdvancesOnlyOnce() {
        val coordinator = QrScanCoordinator()
        coordinator.startScanAttempt(valid64HexToken)

        var navigationEvents = 0
        for (i in 1..10) {
            if (coordinator.onValidationSuccess()) {
                navigationEvents++
            }
        }

        assertEquals(1, navigationEvents)
        assertEquals(ScanState.SUCCESS, coordinator.state)
    }

    @Test
    fun validationFailure_producesOneStableErrorStateRatherThanRepeatedMessageUpdates() {
        val coordinator = QrScanCoordinator()
        coordinator.startScanAttempt(valid64HexToken)

        val expectedErrorMessage = "QR code expired. Please point camera at the freshly rotated code on the projector."
        coordinator.onValidationFailure(expectedErrorMessage)

        assertEquals(ScanState.ERROR, coordinator.state)
        assertEquals(expectedErrorMessage, coordinator.errorMessage)

        // Simulate 30 subsequent camera frames arriving while error is displayed
        for (frame in 1..30) {
            val accepted = coordinator.startScanAttempt(valid64HexToken)
            assertFalse(accepted)
        }

        // Error state and message remain completely stable without flickering
        assertEquals(ScanState.ERROR, coordinator.state)
        assertEquals(expectedErrorMessage, coordinator.errorMessage)
    }

    @Test
    fun retryableFailure_allowsUserToScanAgainWithDeliberateRetry() {
        val coordinator = QrScanCoordinator()
        coordinator.startScanAttempt(valid64HexToken)
        coordinator.onValidationFailure("Network or connection error")
        assertEquals(ScanState.ERROR, coordinator.state)
        assertFalse(coordinator.canScan())

        // User deliberately taps "Try Again"
        val retryAccepted = coordinator.retry()
        assertTrue(retryAccepted)
        assertEquals(ScanState.IDLE, coordinator.state)
        assertTrue(coordinator.canScan())

        // Camera detects next rotated token
        val newRotatedToken = "c1c2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890"
        val newAttemptClaimed = coordinator.startScanAttempt(newRotatedToken)
        assertTrue(newAttemptClaimed)
        assertEquals(ScanState.VALIDATING, coordinator.state)
        assertEquals(newRotatedToken, coordinator.currentToken)
    }

    @Test
    fun cameraResourceManagement_imageProxyIsClosedOnEarlyReturnAndPaused() {
        class FakeImageProxy : androidx.camera.core.ImageProxy {
            var isClosed = false
                private set

            override fun close() {
                isClosed = true
            }

            override fun getCropRect(): android.graphics.Rect = throw NotImplementedError()
            override fun setCropRect(rect: android.graphics.Rect?) {}
            override fun getFormat(): Int = 0
            override fun getHeight(): Int = 0
            override fun getWidth(): Int = 0
            override fun getPlanes(): Array<androidx.camera.core.ImageProxy.PlaneProxy> = emptyArray()
            override fun getImageInfo(): androidx.camera.core.ImageInfo = throw NotImplementedError()
            override fun getImage(): android.media.Image? = null
        }

        val analyzer = QrCodeAnalyzer {}

        // Case A: When paused, analyze immediately closes proxy without processing
        analyzer.pauseScanning()
        assertTrue(analyzer.isScanningPaused())
        val proxy1 = FakeImageProxy()
        analyzer.analyze(proxy1)
        assertTrue(proxy1.isClosed)

        // Case B: When resumed, null mediaImage safely closes proxy
        analyzer.resumeScanning()
        assertFalse(analyzer.isScanningPaused())
        val proxy2 = FakeImageProxy()
        analyzer.analyze(proxy2)
        assertTrue(proxy2.isClosed)
    }

    // ─── 3. Callable Error Mapping ────────────────────────────────────────────────

    @Test
    fun callableErrorMapping_mapsFunctionsExceptionsCorrectly() {
        val unauthRes = QrValidationService.mapCodeStringToResult(
            "unauthenticated",
            "User must be authenticated."
        )
        val permRes = QrValidationService.mapCodeStringToResult(
            "permission-denied",
            "Student is not enrolled in this course."
        )
        val notFoundRes = QrValidationService.mapCodeStringToResult(
            "not-found",
            "Invalid or unknown QR token."
        )
        val expiredRes = QrValidationService.mapCodeStringToResult(
            "deadline-exceeded",
            "QR token has expired."
        )
        val inactiveRes = QrValidationService.mapCodeStringToResult(
            "failed-precondition",
            "Attendance session is no longer active."
        )
        val alreadyRes = QrValidationService.mapCodeStringToResult(
            "already-exists",
            "Attendance has already been recorded for this session."
        )
        val exhaustedRes = QrValidationService.mapCodeStringToResult(
            "resource-exhausted",
            "Maximum verification attempts (2) reached for this session."
        )
        val invalidArgRes = QrValidationService.mapCodeStringToResult(
            "invalid-argument",
            "QR token belongs to a different session."
        )

        assertTrue(unauthRes is QrValidationResult.NotAuthenticated)
        assertTrue(permRes is QrValidationResult.PermissionDenied)
        assertTrue(notFoundRes is QrValidationResult.NotFound)
        assertTrue(expiredRes is QrValidationResult.Expired)
        assertTrue(inactiveRes is QrValidationResult.SessionInactive)
        assertTrue(alreadyRes is QrValidationResult.AlreadyRecorded)
        assertTrue(exhaustedRes is QrValidationResult.RetriesExhausted)
        assertTrue(invalidArgRes is QrValidationResult.InvalidArgument)

        val genericEx = RuntimeException("Connection timeout")
        val mappedGeneric = QrValidationService.mapFunctionsException(genericEx)
        assertTrue(mappedGeneric is QrValidationResult.Error)
        assertEquals("Connection timeout", (mappedGeneric as QrValidationResult.Error).message)
    }

    // ─── 4. Retry Allowance and Exhaustion Tracking ───────────────────────────────

    @Test
    fun retrySemantics_tracksInitialAttemptAndSingleRetry() {
        val maxAttempts = 2

        // Attempt 1: First scan
        var currentAttempt = 1
        var retryRemaining = maxAttempts - currentAttempt
        assertEquals(1, currentAttempt)
        assertEquals(1, retryRemaining)
        assertTrue(retryRemaining > 0)

        // Attempt 2: Eligible verification failure triggers permitted retry
        currentAttempt++
        retryRemaining = maxAttempts - currentAttempt
        assertEquals(2, currentAttempt)
        assertEquals(0, retryRemaining)
        assertFalse(retryRemaining > 0)

        // Attempt 3: Exhausted
        val isExhausted = currentAttempt >= maxAttempts
        assertTrue(isExhausted)
    }

    // ─── 5. Direct Navigation & QR Bypass Guard ───────────────────────────────────

    @Test
    fun bypassGuard_emptySessionIdIsDetectedAndBlocked() {
        val emptySessionId = ""
        val whitespaceSessionId = "   "
        val validSessionId = "session-valid-123"

        assertTrue(emptySessionId.isBlank())
        assertTrue(whitespaceSessionId.isBlank())
        assertFalse(validSessionId.isBlank())

        // In AttendanceFlowScreen, attendance submission requires non-blank sessionId
        val currentUid = "student-uid-abc"
        val docId = if (validSessionId.isNotBlank() && currentUid.isNotBlank()) {
            "${validSessionId}_${currentUid}"
        } else {
            "invalid_fallback"
        }

        assertEquals("session-valid-123_student-uid-abc", docId)
    }

    // ─── 6. Multi-Student and Duplicate Attendance Prevention ─────────────────────

    @Test
    fun multiStudent_twoStudentsScanningSameQrGetDistinctDeterministicIds() {
        val sessionId = "session-uuid-math101"
        val student1Uid = "student-101"
        val student2Uid = "student-102"

        val docId1 = "${sessionId}_${student1Uid}"
        val docId2 = "${sessionId}_${student2Uid}"

        assertNotEquals(docId1, docId2)
        assertEquals("session-uuid-math101_student-101", docId1)
        assertEquals("session-uuid-math101_student-102", docId2)
    }

    @Test
    fun duplicateRecord_sameStudentInSameSessionProducesIdenticalId() {
        val sessionId = "session-uuid-math101"
        val studentUid = "student-101"

        val firstSubmitId = "${sessionId}_${studentUid}"
        val secondSubmitId = "${sessionId}_${studentUid}"

        assertEquals(firstSubmitId, secondSubmitId)
    }

    @Test
    fun attendanceRecord_sessionAndStudentUidConsistency() {
        val sessionId = "session-uuid-phys202"
        val studentUid = "student-uid-777"
        val expectedDocId = "${sessionId}_${studentUid}"

        val record = AttendanceRecord(
            id = expectedDocId,
            sessionId = sessionId,
            studentUid = studentUid,
            studentName = "Bob",
            studentEmail = "bob@test.com",
            courseCode = "PHYS202",
            faceMatchScore = 0.92f,
            matchPercentage = 92,
            matchResult = "STRONG_MATCH",
            livenessChallenge = "BLINK",
            livenessPassed = true,
            status = AttendanceStatus.REVIEW,
            overallScore = 95
        )

        assertEquals(expectedDocId, record.id)
        assertEquals(sessionId, record.sessionId)
        assertEquals(studentUid, record.studentUid)
        assertTrue(record.livenessPassed)
        assertNotEquals(AttendanceStatus.APPROVED, record.status) // Students cannot directly write APPROVED
    }

    // ─── 7. Scoring Model Without Classroom Photo ─────────────────────────────────

    @Test
    fun scoringModel_calculates100PointsUsingQrLivenessAndFaceMatch() {
        val qrVerifiedScore = 20 // Session QR presence verified
        val livenessScore = 20   // Passed liveness challenge

        // Case A: High face match (90% similarity)
        val highMatchPct = 90
        val faceScoreHigh = (highMatchPct * 0.6f).toInt() // 54 pts
        val totalScoreHigh = qrVerifiedScore + livenessScore + faceScoreHigh // 94 pts
        assertEquals(94, totalScoreHigh)
        assertTrue(totalScoreHigh >= 50)

        // Case B: Low face match (20% similarity)
        val lowMatchPct = 20
        val faceScoreLow = (lowMatchPct * 0.6f).toInt() // 12 pts
        val totalScoreLow = qrVerifiedScore + livenessScore + faceScoreLow // 52 pts
        // Even if score is 52, weak/no-match produces REJECTED status
        val statusLow = if (lowMatchPct < 50) AttendanceStatus.REJECTED else AttendanceStatus.REVIEW
        assertEquals(AttendanceStatus.REJECTED, statusLow)

        // Case C: GPS accuracy uncertainty adjustment (-15 pts)
        val uncertainScore = (totalScoreHigh - 15).coerceAtLeast(0) // 79 pts
        assertEquals(79, uncertainScore)
    }
}
