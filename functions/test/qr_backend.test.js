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
const {
  extractCallableParams,
  startAttendanceSession,
  stopAttendanceSession,
  generateQrToken,
  validateQrToken,
  recordVerificationFailure
} = require("../index");

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
        startAttendanceSessionCore(db, studentAuth, { courseCode: COURSE_CODE, latitude: 37.7749, longitude: -122.4194 }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects faculty not assigned to this course", async function () {
      const otherAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, otherAuth, { courseCode: COURSE_CODE, latitude: 37.7749, longitude: -122.4194 }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects session start when classroom coordinates are missing", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE, durationMinutes: 10 }),
        (err) => err.code === "invalid-argument" && err.message.includes("latitude and longitude are required")
      );
    });

    it("rejects session start when classroom coordinates are unset (0.0, 0.0)", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE, durationMinutes: 10, latitude: 0.0, longitude: 0.0 }),
        (err) => err.code === "invalid-argument" && err.message.includes("cannot be unset")
      );
    });

    it("rejects session start when classroom coordinates are out of valid range", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE, durationMinutes: 10, latitude: 95.0, longitude: -122.4194 }),
        (err) => err.code === "invalid-argument" && err.message.includes("out of valid range")
      );
    });

    it("supports explicit location object { latitude, longitude } and verifies locationConfigured presence and demo 100m default", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 10,
        location: {
          latitude: 37.7749,
          longitude: -122.4194
        }
      });
      assert.ok(res.sessionId);
      assert.strictEqual(res.locationConfigured, true);
      assert.strictEqual(res.geofenceRadiusMeters, 100);
      const histDoc = await db.collection("attendance_sessions").doc(res.sessionId).get();
      assert.strictEqual(histDoc.data().locationConfigured, true);
      assert.strictEqual(histDoc.data().location.geofenceRadiusMeters, 100);
      assert.strictEqual(histDoc.data().geofenceRadiusMeters, 100);
      assert.strictEqual(histDoc.data().latitude, 37.7749);

      const pointerDoc = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(pointerDoc.data().location.geofenceRadiusMeters, 100);
      assert.strictEqual(pointerDoc.data().geofenceRadiusMeters, 100);
    });

    it("supports explicit geofence radius override (50m for production mode configuration)", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 10,
        latitude: 37.7749,
        longitude: -122.4194,
        geofenceRadiusMeters: 50
      });
      assert.ok(res.sessionId);
      assert.strictEqual(res.geofenceRadiusMeters, 50);
      const histDoc = await db.collection("attendance_sessions").doc(res.sessionId).get();
      assert.strictEqual(histDoc.data().location.geofenceRadiusMeters, 50);
      assert.strictEqual(histDoc.data().geofenceRadiusMeters, 50);
    });

    it("creates unique session ID and writes both historical and active pointer", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 15,
        latitude: 37.7749,
        longitude: -122.4194
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

    it("verifies that a failed startAttendanceSession call does not produce an active session or partial session record", async function () {
      const countBefore = (await db.collection("attendance_sessions").get()).size;

      // Attempt start with unauthorized faculty
      const otherAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, otherAuth, {
          courseCode: COURSE_CODE,
          durationMinutes: 10,
          latitude: 37.7749,
          longitude: -122.4194
        }),
        (err) => err.code === "permission-denied"
      );

      // Attempt start with invalid/missing coordinates
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, facultyAuth, {
          courseCode: COURSE_CODE,
          durationMinutes: 10
        }),
        (err) => err.code === "invalid-argument"
      );

      // Verify that the existing active pointer was unchanged and no new attendance_sessions record was created
      const pointerDoc = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(pointerDoc.data().sessionId, activeSessionId);
      assert.strictEqual(pointerDoc.data().isActive, true);

      const countAfter = (await db.collection("attendance_sessions").get()).size;
      assert.strictEqual(countAfter, countBefore, "No partial or orphaned attendance_sessions records were created on failure");
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
      assert.strictEqual(response.refreshIntervalMs, 30000);
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

    it("validates token at refresh boundary (26s after issue) within the 30s validity window", async function () {
      const boundaryTokenId = "boundary-token-" + Date.now();
      const now = Date.now();
      // Issued 26 seconds ago, expiring in 4 seconds (total 30s validity)
      await db.collection("qr_tokens").doc(boundaryTokenId).set({
        tokenId: boundaryTokenId,
        sessionId: activeSessionId,
        courseCode: COURSE_CODE,
        facultyUid: FACULTY_UID,
        issuedAt: now - 26000,
        expiresAt: now + 4000
      });
      await db.collection("sessions").doc(COURSE_CODE).update({ currentTokenId: boundaryTokenId });

      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      const res = await validateQrTokenCore(db, studentAuth, {
        tokenId: boundaryTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });
      assert.strictEqual(res.valid, true);
    });

    it("rejects previous QR token immediately once replacement QR is generated", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const firstTokenId = validTokenId;

      // Faculty generates replacement token
      const replacementRes = await generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE });
      const newTokenId = replacementRes.tokenId;
      assert.notStrictEqual(firstTokenId, newTokenId);

      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };

      // Attempting to validate the previous replaced token must fail with deadline-exceeded
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: firstTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        }),
        (err) => err.code === "deadline-exceeded" && err.message.includes("rotated")
      );

      // But the new active token succeeds
      const newRes = await validateQrTokenCore(db, studentAuth, {
        tokenId: newTokenId,
        courseCode: COURSE_CODE,
        sessionId: activeSessionId
      });
      assert.strictEqual(newRes.valid, true);
    });

    it("safely handles concurrent token generation and validation", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const student1Auth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };

      const [tokenRes, validationResult] = await Promise.allSettled([
        generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE }),
        validateQrTokenCore(db, student1Auth, {
          tokenId: validTokenId,
          courseCode: COURSE_CODE,
          sessionId: activeSessionId
        })
      ]);

      assert.strictEqual(tokenRes.status, "fulfilled");
      if (validationResult.status === "rejected") {
        assert.strictEqual(validationResult.reason.code, "deadline-exceeded");
      } else {
        assert.strictEqual(validationResult.value.valid, true);
      }
    });

    it("rejects token past the 30s validity window (31s after issue) with deadline-exceeded", async function () {
      const pastTokenId = "past-token-" + Date.now();
      const now = Date.now();
      // Issued 31 seconds ago, expired 1 second ago (total 30s validity)
      await db.collection("qr_tokens").doc(pastTokenId).set({
        tokenId: pastTokenId,
        sessionId: activeSessionId,
        courseCode: COURSE_CODE,
        facultyUid: FACULTY_UID,
        issuedAt: now - 31000,
        expiresAt: now - 1000
      });

      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: pastTokenId,
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
      const newSession = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        latitude: 37.7749,
        longitude: -122.4194
      });
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

  describe("Exported Callable Cloud Functions Interface (2nd Gen & 1st Gen Compatibility)", function () {
    describe("extractCallableParams unit tests", function () {
      it("normalizes 2nd gen CallableRequest with auth token email", function () {
        const req = {
          data: { courseCode: COURSE_CODE, durationMinutes: 10 },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        };
        const extracted = extractCallableParams(req);
        assert.deepStrictEqual(extracted.data, { courseCode: COURSE_CODE, durationMinutes: 10 });
        assert.deepStrictEqual(extracted.authContext, { uid: FACULTY_UID, email: FACULTY_EMAIL });
      });

      it("normalizes 2nd gen unauthenticated request (auth: null)", function () {
        const req = {
          data: { courseCode: COURSE_CODE },
          auth: null
        };
        const extracted = extractCallableParams(req);
        assert.deepStrictEqual(extracted.data, { courseCode: COURSE_CODE });
        assert.strictEqual(extracted.authContext, null);
      });

      it("normalizes 1st gen (data, context) invocation", function () {
        const data = { courseCode: COURSE_CODE };
        const context = { auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } } };
        const extracted = extractCallableParams(data, context);
        assert.deepStrictEqual(extracted.data, { courseCode: COURSE_CODE });
        assert.deepStrictEqual(extracted.authContext, { uid: FACULTY_UID, email: FACULTY_EMAIL });
      });

      it("normalizes 1st gen unauthenticated invocation", function () {
        const data = { courseCode: COURSE_CODE };
        const extracted = extractCallableParams(data, {});
        assert.deepStrictEqual(extracted.data, { courseCode: COURSE_CODE });
        assert.strictEqual(extracted.authContext, null);
      });

      it("handles null/empty payload safely in 2nd gen format", function () {
        const req = {
          data: null,
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        };
        const extracted = extractCallableParams(req);
        assert.deepStrictEqual(extracted.data, {});
        assert.deepStrictEqual(extracted.authContext, { uid: FACULTY_UID, email: FACULTY_EMAIL });
      });

      it("normalizes 2nd gen CallableRequest when runtime passes responseProxy as arg2", function () {
        const req = {
          data: { courseCode: COURSE_CODE, durationMinutes: 10 },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        };
        const responseProxy = {
          sendChunk: () => {},
          signal: {}
        };
        const extracted = extractCallableParams(req, responseProxy);
        assert.deepStrictEqual(extracted.data, { courseCode: COURSE_CODE, durationMinutes: 10 });
        assert.deepStrictEqual(extracted.authContext, { uid: FACULTY_UID, email: FACULTY_EMAIL });
      });
    });

    describe("Callable entrypoints via .run() with 2nd gen CallableRequest", function () {
      it("startAttendanceSession.run() succeeds with authenticated faculty", async function () {
        const res = await startAttendanceSession.run({
          data: {
            courseCode: COURSE_CODE,
            durationMinutes: 15,
            latitude: 12.9716,
            longitude: 77.5946
          },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        });
        assert.ok(res.sessionId);
        assert.strictEqual(res.courseCode, COURSE_CODE);
        assert.strictEqual(res.latitude, 12.9716);
        assert.strictEqual(res.longitude, 77.5946);
        assert.strictEqual(res.locationConfigured, true);
      });

      it("startAttendanceSession.run() succeeds when runtime supplies responseProxy as arg2 (v7 emulator/live runtime)", async function () {
        const responseProxy = { sendChunk: () => {}, signal: {} };
        const res = await startAttendanceSession.run(
          {
            data: {
              courseCode: COURSE_CODE,
              durationMinutes: 15,
              latitude: 12.9716,
              longitude: 77.5946
            },
            auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
          },
          responseProxy
        );
        assert.ok(res.sessionId);
        assert.strictEqual(res.courseCode, COURSE_CODE);
        assert.strictEqual(res.latitude, 12.9716);
        assert.strictEqual(res.longitude, 77.5946);
        assert.strictEqual(res.locationConfigured, true);
      });

      it("startAttendanceSession.run() with responseProxy REJECTS unauthenticated caller with unauthenticated HttpsError", async function () {
        const responseProxy = { sendChunk: () => {}, signal: {} };
        await assert.rejects(
          startAttendanceSession.run(
            {
              data: {
                courseCode: COURSE_CODE,
                durationMinutes: 10,
                latitude: 12.9716,
                longitude: 77.5946
              },
              auth: null
            },
            responseProxy
          ),
          (err) => err.code === "unauthenticated"
        );
      });

      it("startAttendanceSession.run() with responseProxy REJECTS student caller with permission-denied", async function () {
        const responseProxy = { sendChunk: () => {}, signal: {} };
        await assert.rejects(
          startAttendanceSession.run(
            {
              data: {
                courseCode: COURSE_CODE,
                durationMinutes: 10,
                latitude: 12.9716,
                longitude: 77.5946
              },
              auth: { uid: STUDENT_1_UID, token: { email: STUDENT_1_EMAIL } }
            },
            responseProxy
          ),
          (err) => err.code === "permission-denied"
        );
      });

      it("startAttendanceSession.run() with responseProxy REJECTS unauthorized faculty with permission-denied", async function () {
        const responseProxy = { sendChunk: () => {}, signal: {} };
        await assert.rejects(
          startAttendanceSession.run(
            {
              data: {
                courseCode: COURSE_CODE,
                durationMinutes: 10,
                latitude: 12.9716,
                longitude: 77.5946
              },
              auth: { uid: OTHER_FACULTY_UID, token: { email: OTHER_FACULTY_EMAIL } }
            },
            responseProxy
          ),
          (err) => err.code === "permission-denied"
        );
      });

      it("startAttendanceSession.run() succeeds with authenticated faculty (direct request)", async function () {
        const res = await startAttendanceSession.run({
          data: {
            courseCode: COURSE_CODE,
            durationMinutes: 15,
            latitude: 12.9716,
            longitude: 77.5946
          },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        });
        assert.ok(res.sessionId);
        assert.strictEqual(res.courseCode, COURSE_CODE);
        assert.strictEqual(res.latitude, 12.9716);
        assert.strictEqual(res.longitude, 77.5946);
        assert.strictEqual(res.locationConfigured, true);
      });

      it("startAttendanceSession.run() REJECTS unauthenticated caller with unauthenticated HttpsError", async function () {
        await assert.rejects(
          startAttendanceSession.run({
            data: {
              courseCode: COURSE_CODE,
              durationMinutes: 10,
              latitude: 12.9716,
              longitude: 77.5946
            },
            auth: null
          }),
          (err) => err.code === "unauthenticated"
        );
      });

      it("startAttendanceSession.run() REJECTS student caller with permission-denied", async function () {
        await assert.rejects(
          startAttendanceSession.run({
            data: {
              courseCode: COURSE_CODE,
              durationMinutes: 10,
              latitude: 12.9716,
              longitude: 77.5946
            },
            auth: { uid: STUDENT_1_UID, token: { email: STUDENT_1_EMAIL } }
          }),
          (err) => err.code === "permission-denied"
        );
      });

      it("startAttendanceSession.run() REJECTS unauthorized faculty with permission-denied", async function () {
        await assert.rejects(
          startAttendanceSession.run({
            data: {
              courseCode: COURSE_CODE,
              durationMinutes: 10,
              latitude: 12.9716,
              longitude: 77.5946
            },
            auth: { uid: OTHER_FACULTY_UID, token: { email: OTHER_FACULTY_EMAIL } }
          }),
          (err) => err.code === "permission-denied"
        );
      });

      it("startAttendanceSession.run() REJECTS missing coordinates with invalid-argument", async function () {
        await assert.rejects(
          startAttendanceSession.run({
            data: {
              courseCode: COURSE_CODE,
              durationMinutes: 10
            },
            auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
          }),
          (err) => err.code === "invalid-argument"
        );
      });

      it("generateQrToken.run() generates token for active session with authenticated faculty", async function () {
        const res = await generateQrToken.run({
          data: {
            courseCode: COURSE_CODE,
            sessionId: activeSessionId
          },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        });
        assert.ok(res.tokenId);
        assert.ok(res.expiresAt);
        assert.ok(res.validityMs);
      });

      it("generateQrToken.run() REJECTS unauthenticated caller with unauthenticated", async function () {
        await assert.rejects(
          generateQrToken.run({
            data: {
              courseCode: COURSE_CODE,
              sessionId: activeSessionId
            },
            auth: null
          }),
          (err) => err.code === "unauthenticated"
        );
      });

      it("validateQrToken.run() validates token and creates claim for enrolled student", async function () {
        const tokenRes = await generateQrToken.run({
          data: {
            courseCode: COURSE_CODE,
            sessionId: activeSessionId
          },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        });

        const res = await validateQrToken.run({
          data: {
            tokenId: tokenRes.tokenId,
            courseCode: COURSE_CODE,
            sessionId: activeSessionId
          },
          auth: { uid: STUDENT_1_UID, token: { email: STUDENT_1_EMAIL } }
        });
        assert.strictEqual(res.valid, true);
        assert.strictEqual(res.courseCode, COURSE_CODE);
        assert.strictEqual(res.sessionId, activeSessionId);
        assert.strictEqual(res.claimStatus, "CLAIMED");
      });

      it("validateQrToken.run() REJECTS unauthenticated caller with unauthenticated", async function () {
        await assert.rejects(
          validateQrToken.run({
            data: {
              tokenId: "any-token",
              courseCode: COURSE_CODE,
              sessionId: activeSessionId
            },
            auth: null
          }),
          (err) => err.code === "unauthenticated"
        );
      });

      it("recordVerificationFailure.run() records verification failure for student claim", async function () {
        const tokenRes = await generateQrToken.run({
          data: {
            courseCode: COURSE_CODE,
            sessionId: activeSessionId
          },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        });

        await validateQrToken.run({
          data: {
            tokenId: tokenRes.tokenId,
            courseCode: COURSE_CODE,
            sessionId: activeSessionId
          },
          auth: { uid: STUDENT_1_UID, token: { email: STUDENT_1_EMAIL } }
        });

        const res = await recordVerificationFailure.run({
          data: {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            failureReason: "LIVENESS_FAILED"
          },
          auth: { uid: STUDENT_1_UID, token: { email: STUDENT_1_EMAIL } }
        });
        assert.strictEqual(res.status, "VERIFICATION_FAILED");
        assert.strictEqual(res.retryEligible, true);
      });

      it("recordVerificationFailure.run() REJECTS unauthenticated caller with unauthenticated", async function () {
        await assert.rejects(
          recordVerificationFailure.run({
            data: {
              sessionId: activeSessionId,
              courseCode: COURSE_CODE,
              failureReason: "LIVENESS_FAILED"
            },
            auth: null
          }),
          (err) => err.code === "unauthenticated"
        );
      });

      it("stopAttendanceSession.run() deactivates session with authenticated faculty", async function () {
        const res = await stopAttendanceSession.run({
          data: {
            courseCode: COURSE_CODE,
            sessionId: activeSessionId
          },
          auth: { uid: FACULTY_UID, token: { email: FACULTY_EMAIL } }
        });
        assert.strictEqual(res.success, true);
        assert.strictEqual(res.sessionId, activeSessionId);
      });

      it("stopAttendanceSession.run() REJECTS unauthenticated caller with unauthenticated", async function () {
        await assert.rejects(
          stopAttendanceSession.run({
            data: {
              courseCode: COURSE_CODE,
              sessionId: activeSessionId
            },
            auth: null
          }),
          (err) => err.code === "unauthenticated"
        );
      });
    });
  });
});
