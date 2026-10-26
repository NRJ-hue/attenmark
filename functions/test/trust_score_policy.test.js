const assert = require("assert");
const http = require("http");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

const {
  submitAttendanceRecordCore,
  startAttendanceSessionCore
} = require("../qr_service");
const { submitAttendanceRecord } = require("../index");
const app = require("../server");

describe("Trust-Score Policy and Authoritative Attendance Submission", function () {
  this.timeout(15000);

  let db;
  let server;
  let baseUrl;

  const FACULTY_UID = "fac-trust-owner";
  const FACULTY_EMAIL = "prof.trust@college.edu";
  const STUDENT_UID = "21truststudent-uid";
  const STUDENT_EMAIL = "21truststudent@college.edu";
  const OTHER_STUDENT_UID = "21otherstudent-uid";
  const OTHER_STUDENT_EMAIL = "21otherstudent@college.edu";

  const COURSE_CODE = "TS101";
  const COURSE_ID = "course-ts101-doc";
  let activeSessionId;

  before(async function () {
    process.env.NODE_ENV = "test";
    process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
    process.env.GCLOUD_PROJECT = "attendmark-c2f07";

    if (!getApps().length) {
      initializeApp({ projectId: "attendmark-c2f07" });
    }
    db = getFirestore();

    // Start ephemeral HTTP test server
    server = http.createServer(app);
    await new Promise((resolve) => {
      server.listen(0, "127.0.0.1", () => {
        const port = server.address().port;
        baseUrl = `http://127.0.0.1:${port}`;
        resolve();
      });
    });
  });

  after(function (done) {
    if (server) {
      server.close(done);
    } else {
      done();
    }
  });

  beforeEach(async function () {
    activeSessionId = "session-ts-" + Date.now();

    // Setup user profiles
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty",
      email: FACULTY_EMAIL,
      name: "Prof. Trust"
    });

    await db.collection("users").doc(STUDENT_UID).set({
      role: "student",
      email: STUDENT_EMAIL,
      name: "Trust Student"
    });

    await db.collection("users").doc(OTHER_STUDENT_UID).set({
      role: "student",
      email: OTHER_STUDENT_EMAIL,
      name: "Other Student"
    });

    // Setup course
    await db.collection("courses").doc(COURSE_ID).set({
      name: "Trust Score Testing",
      classCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      enrolledStudents: [STUDENT_EMAIL, OTHER_STUDENT_EMAIL],
      isArchived: false,
      createdAt: Date.now()
    });

    // Start attendance session
    await startAttendanceSessionCore(
      db,
      { uid: FACULTY_UID, email: FACULTY_EMAIL },
      {
        courseCode: COURSE_CODE,
        durationMinutes: 15,
        latitude: 37.7749,
        longitude: -122.4194,
        sessionId: activeSessionId
      }
    );

    // Create a valid active attendance claim for STUDENT_UID
    const claimId = `${activeSessionId}_${STUDENT_UID}`;
    await db.collection("attendance_claims").doc(claimId).set({
      sessionId: activeSessionId,
      studentUid: STUDENT_UID,
      studentEmail: STUDENT_EMAIL,
      courseCode: COURSE_CODE,
      status: "CLAIMED",
      attemptCount: 1,
      maxAttempts: 2,
      claimedAt: Date.now(),
      updatedAt: Date.now()
    });
  });

  // ─── 1. Exact Threshold Policy & Boundaries ────────────────────────────────────

  describe("Exact Threshold Policy Boundaries", function () {
    it("Score 100 -> APPROVED (autoApproved = true)", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL, displayName: "Trust Student" },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 100,
          matchPercentage: 100,
          matchResult: "MATCH",
          livenessChallenge: "SMILE",
          livenessPassed: true,
          faceMatchScore: 1.0
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "APPROVED");
      assert.strictEqual(res.autoApproved, true);
      assert.strictEqual(res.overallScore, 100);

      // Verify stored record in Firestore
      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.exists, true);
      assert.strictEqual(doc.data().status, "APPROVED");
      assert.strictEqual(doc.data().autoApproved, true);
      assert.strictEqual(doc.data().rejectionReason, null);

      // Verify claim consumed
      const claim = await db.collection("attendance_claims").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(claim.data().status, "SUBMITTED");
      assert.strictEqual(claim.data().finalStatus, "APPROVED");
    });

    it("Score 76 -> APPROVED (autoApproved = true at threshold boundary)", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 76,
          matchPercentage: 76,
          matchResult: "MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: true
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "APPROVED");
      assert.strictEqual(res.autoApproved, true);
    });

    it("Score 75 -> REVIEW (autoApproved = false at upper review boundary)", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 75,
          matchPercentage: 75,
          matchResult: "MATCH",
          livenessChallenge: "TURN_LEFT",
          livenessPassed: true
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REVIEW");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REVIEW");
      assert.strictEqual(doc.data().autoApproved, false);
    });

    it("Score 40 -> REVIEW (autoApproved = false at lower review boundary)", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 40,
          matchPercentage: 40,
          matchResult: "MATCH",
          livenessChallenge: "TURN_RIGHT",
          livenessPassed: true
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REVIEW");
      assert.strictEqual(res.autoApproved, false);
    });

    it("Score 39 -> REJECTED (autoApproved = false below minimum threshold)", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 39,
          matchPercentage: 39,
          matchResult: "MATCH",
          livenessChallenge: "NOD",
          livenessPassed: true
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REJECTED");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REJECTED");
      assert.strictEqual(doc.data().autoApproved, false);
      assert(doc.data().rejectionReason.includes("39/100"));
    });

    it("Score 0 -> REJECTED", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 0,
          matchPercentage: 0,
          matchResult: "MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: true
        }
      );

      assert.strictEqual(res.status, "REJECTED");
      assert.strictEqual(res.autoApproved, false);
    });
  });

  // ─── 2. Mandatory Verification Gates (Bypass Prevention) ───────────────────────

  describe("Mandatory Verification Gates", function () {
    it("High score (95) with failed liveness check is REJECTED", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 95,
          matchPercentage: 95,
          matchResult: "MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: false // Failed gate!
        }
      );

      assert.strictEqual(res.status, "REJECTED");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REJECTED");
      assert(doc.data().rejectionReason.includes("Liveness"));
    });

    it("High score (90) with NO_MATCH face verification is REJECTED", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 90,
          matchPercentage: 20,
          matchResult: "NO_MATCH", // Failed gate!
          livenessChallenge: "SMILE",
          livenessPassed: true
        }
      );

      assert.strictEqual(res.status, "REJECTED");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REJECTED");
      assert(doc.data().rejectionReason.includes("Face"));
    });

    it("Claim marked REJECTED_GPS cannot submit attendance even with score 99", async function () {
      // Mark claim as GPS rejected
      const claimId = `${activeSessionId}_${STUDENT_UID}`;
      await db.collection("attendance_claims").doc(claimId).update({
        status: "REJECTED_GPS",
        failureReason: "GPS_OUT_OF_BOUNDS"
      });

      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          { uid: STUDENT_UID, email: STUDENT_EMAIL },
          {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            overallScore: 99,
            matchPercentage: 99,
            matchResult: "MATCH",
            livenessChallenge: "BLINK",
            livenessPassed: true
          }
        ),
        (err) => err.code === "permission-denied" && err.message.includes("GPS")
      );
    });

    it("High score (77) with UNCERTAIN_ACCURACY GPS routes to REVIEW, not REJECTED or auto-APPROVED", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 77,
          matchPercentage: 90,
          matchResult: "STRONG_MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: true,
          gpsVerificationStatus: "UNCERTAIN_ACCURACY",
          rejectionReason: "GPS accuracy is inconclusive; awaiting faculty review."
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REVIEW");
      assert.strictEqual(res.autoApproved, false);
      assert.strictEqual(res.overallScore, 77);
      assert(res.rejectionReason.includes("inconclusive") || res.rejectionReason.includes("uncertain"));

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REVIEW");
      assert.strictEqual(doc.data().autoApproved, false);
      assert.strictEqual(doc.data().gpsVerificationStatus, "UNCERTAIN_ACCURACY");
      assert(doc.data().rejectionReason.includes("inconclusive"));
    });

    it("High score (85) with uncertain distance message routes to REVIEW, not REJECTED", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 85,
          matchPercentage: 95,
          matchResult: "STRONG_MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: true,
          rejectionReason: "GPS position is uncertain: distance is 35m with ±20m accuracy for a 50m geofence."
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REVIEW");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REVIEW");
      assert.strictEqual(doc.data().autoApproved, false);
      assert(doc.data().rejectionReason.includes("uncertain"));
    });

    it("Low score (35) with UNCERTAIN_ACCURACY GPS remains REJECTED (<40 threshold)", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 35,
          matchPercentage: 25,
          matchResult: "WEAK_MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: true,
          gpsVerificationStatus: "UNCERTAIN_ACCURACY",
          rejectionReason: "GPS accuracy is inconclusive; awaiting faculty review."
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REJECTED");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REJECTED");
    });

    it("High score (85) with explicit geofence violation is REJECTED", async function () {
      const res = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 85,
          matchPercentage: 95,
          matchResult: "STRONG_MATCH",
          livenessChallenge: "BLINK",
          livenessPassed: true,
          rejectionReason: "Location mismatch: You are 75m from the classroom (outside permitted geofence of 50m)."
        }
      );

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REJECTED");
      assert.strictEqual(res.autoApproved, false);

      const doc = await db.collection("attendance_records").doc(`${activeSessionId}_${STUDENT_UID}`).get();
      assert.strictEqual(doc.data().status, "REJECTED");
      assert(doc.data().rejectionReason.includes("Location mismatch"));
    });
  });

  // ─── 3. Validation & Security Enforcement ─────────────────────────────────────

  describe("Validation & Security Enforcement", function () {
    it("rejects unauthenticated caller with unauthenticated", async function () {
      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          null,
          {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            overallScore: 80
          }
        ),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects missing sessionId or courseCode with invalid-argument", async function () {
      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          { uid: STUDENT_UID, email: STUDENT_EMAIL },
          { courseCode: COURSE_CODE, overallScore: 80 }
        ),
        (err) => err.code === "invalid-argument"
      );

      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          { uid: STUDENT_UID, email: STUDENT_EMAIL },
          { sessionId: activeSessionId, overallScore: 80 }
        ),
        (err) => err.code === "invalid-argument"
      );
    });

    it("rejects invalid score values (-1, 101, float, string) with invalid-argument", async function () {
      const invalidScores = [-1, 101, 85.5, "85", null, undefined, NaN];

      for (const score of invalidScores) {
        await assert.rejects(
          submitAttendanceRecordCore(
            db,
            { uid: STUDENT_UID, email: STUDENT_EMAIL },
            {
              sessionId: activeSessionId,
              courseCode: COURSE_CODE,
              overallScore: score
            }
          ),
          (err) => err.code === "invalid-argument",
          `Expected invalid-argument for score ${score}`
        );
      }
    });

    it("rejects submission without an existing attendance claim with failed-precondition", async function () {
      // OTHER_STUDENT_UID has no claim created
      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          { uid: OTHER_STUDENT_UID, email: OTHER_STUDENT_EMAIL },
          {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            overallScore: 80,
            livenessPassed: true
          }
        ),
        (err) => err.code === "failed-precondition"
      );
    });

    it("rejects submission for an archived course with failed-precondition", async function () {
      await db.collection("courses").doc(COURSE_ID).update({ isArchived: true });

      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          { uid: STUDENT_UID, email: STUDENT_EMAIL },
          {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            overallScore: 85,
            livenessPassed: true
          }
        ),
        (err) => err.code === "failed-precondition" && err.message.includes("archived")
      );
    });

    it("rejects caller trying to claim another student's record with permission-denied", async function () {
      // Claim belongs to STUDENT_UID; caller is OTHER_STUDENT_UID targeting STUDENT_UID's doc
      const claimId = `${activeSessionId}_${OTHER_STUDENT_UID}`;
      await db.collection("attendance_claims").doc(claimId).set({
        sessionId: activeSessionId,
        studentUid: STUDENT_UID, // Mismatched UID on purpose
        courseCode: COURSE_CODE,
        status: "CLAIMED"
      });

      await assert.rejects(
        submitAttendanceRecordCore(
          db,
          { uid: OTHER_STUDENT_UID, email: OTHER_STUDENT_EMAIL },
          {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            overallScore: 85,
            livenessPassed: true
          }
        ),
        (err) => err.code === "permission-denied"
      );
    });

    it("handles duplicate submissions idempotently without creating duplicate documents", async function () {
      // First submission
      const res1 = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 80,
          livenessPassed: true
        }
      );
      assert.strictEqual(res1.status, "APPROVED");
      assert.strictEqual(res1.alreadySubmitted, false);

      // Duplicate submission
      const res2 = await submitAttendanceRecordCore(
        db,
        { uid: STUDENT_UID, email: STUDENT_EMAIL },
        {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 80,
          livenessPassed: true
        }
      );
      assert.strictEqual(res2.status, "APPROVED");
      assert.strictEqual(res2.alreadySubmitted, true);
    });
  });

  // ─── 4. Callable Function Integration ─────────────────────────────────────────

  describe("Cloud Function Callable (submitAttendanceRecord.run)", function () {
    it("submits attendance and enforces auto-approval via Callable SDK", async function () {
      const res = await submitAttendanceRecord.run({
        data: {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 92,
          livenessPassed: true,
          matchResult: "MATCH"
        },
        auth: { uid: STUDENT_UID, token: { email: STUDENT_EMAIL } }
      });

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "APPROVED");
      assert.strictEqual(res.autoApproved, true);
    });

    it("submits attendance with review status for score 65 via Callable SDK", async function () {
      // Use OTHER_STUDENT_UID for this test
      const claimId = `${activeSessionId}_${OTHER_STUDENT_UID}`;
      await db.collection("attendance_claims").doc(claimId).set({
        sessionId: activeSessionId,
        studentUid: OTHER_STUDENT_UID,
        studentEmail: OTHER_STUDENT_EMAIL,
        courseCode: COURSE_CODE,
        status: "CLAIMED",
        attemptCount: 1,
        maxAttempts: 2,
        claimedAt: Date.now()
      });

      const res = await submitAttendanceRecord.run({
        data: {
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 65,
          livenessPassed: true,
          matchResult: "MATCH"
        },
        auth: { uid: OTHER_STUDENT_UID, token: { email: OTHER_STUDENT_EMAIL } }
      });

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.status, "REVIEW");
      assert.strictEqual(res.autoApproved, false);
    });

    it("rejects unauthenticated caller via Callable SDK", async function () {
      await assert.rejects(
        submitAttendanceRecord.run({
          data: {
            sessionId: activeSessionId,
            courseCode: COURSE_CODE,
            overallScore: 80
          },
          auth: null
        }),
        (err) => err.code === "unauthenticated"
      );
    });
  });

  // ─── 5. HTTP API Endpoint Integration ─────────────────────────────────────────

  describe("HTTP API Endpoints", function () {
    it("POST /api/submitAttendanceRecord successfully persists APPROVED record for score 85", async function () {
      const res = await fetch(`${baseUrl}/api/submitAttendanceRecord`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock-token",
          "x-test-auth-uid": STUDENT_UID,
          "x-test-auth-email": STUDENT_EMAIL
        },
        body: JSON.stringify({
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 85,
          livenessPassed: true,
          matchResult: "MATCH",
          matchPercentage: 85
        })
      });

      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.success, true);
      assert.strictEqual(json.status, "APPROVED");
      assert.strictEqual(json.autoApproved, true);
    });

    it("POST /api/submitAttendanceRecord returns 400 on invalid score value", async function () {
      const res = await fetch(`${baseUrl}/api/submitAttendanceRecord`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock-token",
          "x-test-auth-uid": STUDENT_UID,
          "x-test-auth-email": STUDENT_EMAIL
        },
        body: JSON.stringify({
          sessionId: activeSessionId,
          courseCode: COURSE_CODE,
          overallScore: 999
        })
      });

      assert.strictEqual(res.status, 400);
      const json = await res.json();
      assert.strictEqual(json.error.code, "invalid-argument");
    });
  });
});
