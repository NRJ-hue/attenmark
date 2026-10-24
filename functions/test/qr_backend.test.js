const assert = require("assert");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const config = require("../config");
const {
  startAttendanceSessionCore,
  stopAttendanceSessionCore,
  generateQrTokenCore,
  validateQrTokenCore,
  recordVerificationFailureCore
} = require("../qr_service");

// Configure admin to point to the local Firestore emulator
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
process.env.GCLOUD_PROJECT = "attendmark-c2f07";

if (!getApps().length) {
  initializeApp({ projectId: "attendmark-c2f07" });
}

const db = getFirestore();

describe("Backend Cloud Functions & Session Lifecycle", function () {
  this.timeout(10000);

  const FACULTY_UID = "fac-uid-101";
  const FACULTY_EMAIL = "faculty@test.com";

  const OTHER_FACULTY_UID = "fac-uid-999";
  const OTHER_FACULTY_EMAIL = "otherfaculty@test.com";

  const STUDENT_1_UID = "stu-uid-201";
  const STUDENT_1_EMAIL = "student1@test.com";

  const STUDENT_2_UID = "stu-uid-202";
  const STUDENT_2_EMAIL = "student2@test.com";

  const UNENROLLED_STUDENT_UID = "stu-uid-303";
  const UNENROLLED_STUDENT_EMAIL = "stranger@test.com";

  const COURSE_CODE = "CS101";
  const COURSE_ID = "course-cs101-doc";
  let activeSessionId;

  beforeEach(async function () {
    activeSessionId = "session-uuid-" + Date.now();

    // Setup User profiles
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty",
      email: FACULTY_EMAIL,
      name: "Prof. Smith"
    });

    await db.collection("users").doc(OTHER_FACULTY_UID).set({
      role: "faculty",
      email: OTHER_FACULTY_EMAIL,
      name: "Prof. Other"
    });

    await db.collection("users").doc(STUDENT_1_UID).set({
      role: "student",
      email: STUDENT_1_EMAIL,
      name: "Alice"
    });

    await db.collection("users").doc(STUDENT_2_UID).set({
      role: "student",
      email: STUDENT_2_EMAIL,
      name: "Bob"
    });

    await db.collection("users").doc(UNENROLLED_STUDENT_UID).set({
      role: "student",
      email: UNENROLLED_STUDENT_EMAIL,
      name: "Stranger"
    });

    // Setup Course with enrolled students
    await db.collection("courses").doc(COURSE_ID).set({
      name: "Computer Science 101",
      classCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      enrolledStudents: [STUDENT_1_EMAIL, STUDENT_2_EMAIL]
    });

    // Setup Active Session pointer in sessions/{courseCode}
    await db.collection("sessions").doc(COURSE_CODE).set({
      sessionId: activeSessionId,
      courseCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      isActive: true,
      startedAt: Date.now(),
      expiresAt: Date.now() + 600000 // 10 minutes
    });

    // Setup Historical Session record in attendance_sessions/{sessionId}
    await db.collection("attendance_sessions").doc(activeSessionId).set({
      sessionId: activeSessionId,
      courseCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      isActive: true,
      startedAt: Date.now(),
      expiresAt: Date.now() + 600000
    });
  });

  // ─── 1. startAttendanceSession Tests ───────────────────────────────────────

  describe("startAttendanceSession", function () {
    it("rejects unauthenticated caller", async function () {
      await assert.rejects(
        startAttendanceSessionCore(db, null, { courseCode: COURSE_CODE }),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects student caller attempting to start session", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, studentAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects faculty not assigned to this course", async function () {
      const otherAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, otherAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("creates unique session ID and writes both historical and active pointer", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 15
      });

      assert.ok(res.sessionId);
      assert.notStrictEqual(res.sessionId, activeSessionId);
      assert.strictEqual(res.courseCode, COURSE_CODE);
      assert.strictEqual(res.durationMinutes, 15);

      // Verify historical record
      const histDoc = await db.collection("attendance_sessions").doc(res.sessionId).get();
      assert.ok(histDoc.exists);
      assert.strictEqual(histDoc.data().isActive, true);
      assert.strictEqual(histDoc.data().sessionId, res.sessionId);

      // Verify active pointer
      const pointerDoc = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.ok(pointerDoc.exists);
      assert.strictEqual(pointerDoc.data().sessionId, res.sessionId);
      assert.strictEqual(pointerDoc.data().isActive, true);
    });
  });

  // ─── 2. stopAttendanceSession Tests ────────────────────────────────────────

  describe("stopAttendanceSession", function () {
    it("rejects unauthenticated caller", async function () {
      await assert.rejects(
        stopAttendanceSessionCore(db, null, { courseCode: COURSE_CODE }),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects non-owner faculty from stopping session", async function () {
      const otherAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        stopAttendanceSessionCore(db, otherAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("atomically deactivates active pointer and historical session", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await stopAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });
      assert.strictEqual(res.success, true);
      assert.strictEqual(res.sessionId, activeSessionId);

      // Check active pointer
      const pointerDoc = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(pointerDoc.data().isActive, false);
      assert.ok(pointerDoc.data().stoppedAt);

      // Check historical doc
      const histDoc = await db.collection("attendance_sessions").doc(activeSessionId).get();
      assert.strictEqual(histDoc.data().isActive, false);
      assert.ok(histDoc.data().stoppedAt);
    });
  });

  // ─── 3. generateQrToken Tests ──────────────────────────────────────────────

  describe("generateQrToken", function () {
    it("rejects unauthenticated caller with unauthenticated error", async function () {
      await assert.rejects(
        generateQrTokenCore(db, null, { courseCode: COURSE_CODE }),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects student caller attempting to generate a QR with permission-denied", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        generateQrTokenCore(db, studentAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects faculty attempting to manage another faculty's session", async function () {
      const otherFacultyAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        generateQrTokenCore(db, otherFacultyAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects token generation when session is inactive", async function () {
      await db.collection("sessions").doc(COURSE_CODE).update({ isActive: false });
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      await assert.rejects(
        generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "failed-precondition"
      );
    });

    it("rejects token generation when session has expired", async function () {
      await db.collection("sessions").doc(COURSE_CODE).update({ expiresAt: Date.now() - 1000 });
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      await assert.rejects(
        generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "failed-precondition"
      );
    });

    it("generates a cryptographically secure, opaque token with server expiry", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const response = await generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE });

      assert.ok(response.tokenId, "Token ID must be present");
      assert.strictEqual(typeof response.tokenId, "string");
      assert.strictEqual(response.tokenId.length, 64, "Token should be 32 hex bytes (64 chars)");
      assert.strictEqual(response.validityMs, 30000);
      assert.strictEqual(response.refreshIntervalMs, 25000);
      assert.ok(response.expiresAt > Date.now(), "Token expiry must be in the future");

      // Verify token is stored in Firestore
      const tokenDoc = await db.collection("qr_tokens").doc(response.tokenId).get();
      assert.ok(tokenDoc.exists, "Token document must exist in Firestore");
      const tokenData = tokenDoc.data();
      assert.strictEqual(tokenData.sessionId, activeSessionId);
      assert.strictEqual(tokenData.courseCode, COURSE_CODE);
      assert.strictEqual(tokenData.facultyUid, FACULTY_UID);
    });
  });

  // ─── 4. validateQrToken Tests ──────────────────────────────────────────────

  describe("validateQrToken", function () {
    let validTokenId;

    beforeEach(async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE });
      validTokenId = res.tokenId;
    });

    it("rejects unauthenticated student", async function () {
      await assert.rejects(
        validateQrTokenCore(db, null, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects faculty attempting to validate student QR", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, facultyAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects student not enrolled in the course", async function () {
      const strangerAuth = { uid: UNENROLLED_STUDENT_UID, email: UNENROLLED_STUDENT_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, strangerAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects non-existent QR token with not-found", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: "fake-token-id-12345",
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "not-found"
      );
    });

    it("rejects expired QR token with deadline-exceeded", async function () {
      const expiredTokenId = "expired-token-" + Date.now();
      await db.collection("qr_tokens").doc(expiredTokenId).set({
        tokenId: expiredTokenId,
        sessionId: activeSessionId,
        courseCode: COURSE_CODE,
        facultyUid: FACULTY_UID,
        issuedAt: Date.now() - 40000,
        expiresAt: Date.now() - 10000 // Expired 10s ago
      });

      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: expiredTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "deadline-exceeded"
      );
    });

    it("rejects token when session is closed or inactive", async function () {
      await db.collection("sessions").doc(COURSE_CODE).update({ isActive: false });

      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "failed-precondition"
      );
    });

    it("rejects token for mismatched course or session", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };

      // Wrong courseCode
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: "WRONG_COURSE",
          sessionId: activeSessionId
        }),
        (err) => err.code === "not-found" || err.code === "invalid-argument"
      );

      // Wrong sessionId
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: "wrong-session-id"
        }),
        (err) => err.code === "invalid-argument"
      );
    });

    it("REGRESSION TEST: rejects old token from a previous session after a new session starts", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      // Old token was issued for activeSessionId (Session 1).
      // Now faculty starts Session 2:
      const newSession = await startAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });
      const newSessionId = newSession.sessionId;

      // Case A: Student attempts to validate old token with newSessionId
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: newSessionId
        }),
        (err) => err.code === "invalid-argument"
      );

      // Case B: Student attempts to validate old token with old activeSessionId
      // (The active pointer in sessions/{courseCode} has moved to newSessionId!)
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "invalid-argument"
      );
    });

    it("allows multiple eligible students to use the same valid QR", async function () {
      const student1Auth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      const student2Auth = { uid: STUDENT_2_UID, email: STUDENT_2_EMAIL };

      const res1 = await validateQrTokenCore(db, student1Auth, {
        tokenId: validTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });

      const res2 = await validateQrTokenCore(db, student2Auth, {
        tokenId: validTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });

      assert.strictEqual(res1.valid, true);
      assert.strictEqual(res1.attempt, 1);
      assert.strictEqual(res2.valid, true);
      assert.strictEqual(res2.attempt, 1);

      // Verify separate claim documents exist for both students
      const claim1Doc = await db.collection("attendance_claims").doc(`${activeSessionId}_${STUDENT_1_UID}`).get();
      const claim2Doc = await db.collection("attendance_claims").doc(`${activeSessionId}_${STUDENT_2_UID}`).get();

      assert.ok(claim1Doc.exists, "Student 1 claim must exist");
      assert.ok(claim2Doc.exists, "Student 2 claim must exist");
    });

    it("supports controlled retry lifecycle: re-scans before failure do not consume retries, failure allows one retry, and limit is enforced", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };

      // Step 1: Initial valid scan creates claim
      const firstScan = await validateQrTokenCore(db, studentAuth, {
        tokenId: validTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });
      assert.strictEqual(firstScan.valid, true);
      assert.strictEqual(firstScan.attempt, 1);
      assert.strictEqual(firstScan.retryRemaining, 1);

      // Step 2: Duplicate scan / frame detection BEFORE an eligible failure occurs
      // MUST NOT consume retry!
      const duplicateScan = await validateQrTokenCore(db, studentAuth, {
        tokenId: validTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });
      assert.strictEqual(duplicateScan.valid, true);
      assert.strictEqual(duplicateScan.attempt, 1, "Attempt must not increment before failure");
      assert.strictEqual(duplicateScan.retryRemaining, 1);

      // Step 3: Record an eligible capture/biometric verification failure
      const failureResult = await recordVerificationFailureCore(db, studentAuth, {
        sessionId: activeSessionId,
        courseCode: COURSE_CODE,
        failureReason: "FACE_MISMATCH"
      });
      assert.strictEqual(failureResult.status, "VERIFICATION_FAILED");
      assert.strictEqual(failureResult.retryEligible, true);

      // Step 4: Re-scanning now grants the single permitted retry
      const retryScan = await validateQrTokenCore(db, studentAuth, {
        tokenId: validTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });
      assert.strictEqual(retryScan.valid, true);
      assert.strictEqual(retryScan.attempt, 2);
      assert.strictEqual(retryScan.retryRemaining, 0);

      // Step 5: Second verification attempt fails
      const secondFailure = await recordVerificationFailureCore(db, studentAuth, {
        sessionId: activeSessionId,
        courseCode: COURSE_CODE,
        failureReason: "LIVENESS_FAILED"
      });
      assert.strictEqual(secondFailure.status, "VERIFICATION_FAILED");
      assert.strictEqual(secondFailure.retryEligible, false);

      // Step 6: Attempt 3 exceeds max retries -> Rejected with resource-exhausted
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "resource-exhausted"
      );
    });

    it("DENIES retries for GPS location violations outside the classroom", async function () {
      const studentAuth = { uid: STUDENT_2_UID, email: STUDENT_2_EMAIL };

      // Initial scan
      await validateQrTokenCore(db, studentAuth, {
        tokenId: validTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });

      // Record GPS violation
      const gpsFailure = await recordVerificationFailureCore(db, studentAuth, {
        sessionId: activeSessionId,
        courseCode: COURSE_CODE,
        failureReason: "GPS_OUTSIDE_GEOFENCE"
      });
      assert.strictEqual(gpsFailure.status, "REJECTED_GPS");
      assert.strictEqual(gpsFailure.retryEligible, false);

      // Attempting to re-scan after GPS violation is rejected
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects claims if final attendance record has already been submitted", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };

      // Pre-record final attendance record
      await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_1_UID}`).set({
        sessionId: activeSessionId,
        studentUid: STUDENT_1_UID,
        studentEmail: STUDENT_1_EMAIL,
        courseCode: COURSE_CODE,
        status: "REVIEW",
        timestamp: Date.now()
      });

      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "already-exists"
      );
    });
  });
});
