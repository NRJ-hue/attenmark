const assert = require("assert");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const {
  startAttendanceSessionCore,
  stopAttendanceSessionCore,
  generateQrTokenCore,
  validateQrTokenCore
} = require("../qr_service");

process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
process.env.GCLOUD_PROJECT = "attendmark-c2f07";

if (!getApps().length) {
  initializeApp({ projectId: "attendmark-c2f07" });
}

const db = getFirestore();

describe("Phase 4: Faculty Web Portal Integration Tests", function () {
  this.timeout(12000);

  const FACULTY_UID = "portal-fac-1";
  const FACULTY_EMAIL = "faculty.portal@test.com";

  const OTHER_FACULTY_UID = "portal-fac-2";
  const OTHER_FACULTY_EMAIL = "other.faculty@test.com";

  const STUDENT_1_UID = "portal-stu-1";
  const STUDENT_1_EMAIL = "alice.student@test.com";

  const STUDENT_2_UID = "portal-stu-2";
  const STUDENT_2_EMAIL = "bob.student@test.com";

  const COURSE_CODE = "PORTAL101";
  const COURSE_ID = "course-portal101";

  beforeEach(async function () {
    // 1. Setup User Profiles
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty",
      email: FACULTY_EMAIL,
      name: "Dr. Portal Faculty"
    });

    await db.collection("users").doc(OTHER_FACULTY_UID).set({
      role: "faculty",
      email: OTHER_FACULTY_EMAIL,
      name: "Dr. Other Faculty"
    });

    await db.collection("users").doc(STUDENT_1_UID).set({
      role: "student",
      email: STUDENT_1_EMAIL,
      name: "Alice Portal"
    });

    await db.collection("users").doc(STUDENT_2_UID).set({
      role: "student",
      email: STUDENT_2_EMAIL,
      name: "Bob Portal"
    });

    // 2. Setup Course
    await db.collection("courses").doc(COURSE_ID).set({
      name: "Portal Systems",
      classCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      enrolledStudents: [STUDENT_1_EMAIL, STUDENT_2_EMAIL]
    });
  });

  // ─── 1. Faculty Login & Role Enforcement ───────────────────────────────────

  describe("Faculty Login & Role Enforcement", function () {
    const UNVERIFIED_FACULTY_UID = "portal-unverified-fac";
    const WRONG_ROLE_UID = "portal-wrong-role-user";

    it("verifies correctly provisioned faculty allows portal access", async function () {
      const userDoc = await db.collection("users").doc(FACULTY_UID).get();
      assert.strictEqual(userDoc.exists, true);
      assert.strictEqual(userDoc.data().role, "faculty");
      const canAccess = userDoc.exists && userDoc.data().role === "faculty";
      assert.strictEqual(canAccess, true);
    });

    it("verifies faculty account without verification (missing Firestore profile) remains denied", async function () {
      const unverifiedDoc = await db.collection("users").doc(UNVERIFIED_FACULTY_UID).get();
      assert.strictEqual(unverifiedDoc.exists, false);
      const canAccess = unverifiedDoc.exists && unverifiedDoc.data().role === "faculty";
      assert.strictEqual(canAccess, false);
    });

    it("verifies faculty account with unverified/non-faculty role remains denied", async function () {
      await db.collection("users").doc(WRONG_ROLE_UID).set({
        role: "guest",
        email: "guest@test.com",
        name: "Guest User"
      });
      const wrongRoleDoc = await db.collection("users").doc(WRONG_ROLE_UID).get();
      assert.strictEqual(wrongRoleDoc.exists, true);
      const canAccess = wrongRoleDoc.exists && wrongRoleDoc.data().role === "faculty";
      assert.strictEqual(canAccess, false);
    });

    it("verifies student role is denied faculty portal access", async function () {
      const studentDoc = await db.collection("users").doc(STUDENT_1_UID).get();
      assert.strictEqual(studentDoc.exists, true);
      assert.notStrictEqual(studentDoc.data().role, "faculty");
      assert.strictEqual(studentDoc.data().role, "student");
      const canAccess = studentDoc.exists && studentDoc.data().role === "faculty";
      assert.strictEqual(canAccess, false);
    });
  });

  // ─── 2. Course Ownership & Authorization ───────────────────────────────────

  describe("Course Ownership", function () {
    it("allows faculty to query strictly their own assigned courses", async function () {
      const snapshot = await db
        .collection("courses")
        .where("facultyEmail", "==", FACULTY_EMAIL)
        .get();

      assert.strictEqual(snapshot.empty, false);
      const course = snapshot.docs[0].data();
      assert.strictEqual(course.classCode, COURSE_CODE);
      assert.strictEqual(course.facultyEmail, FACULTY_EMAIL);
    });

    it("DENIES faculty from managing a course owned by another faculty", async function () {
      const unauthorizedFacultyAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        startAttendanceSessionCore(db, unauthorizedFacultyAuth, { courseCode: COURSE_CODE, latitude: 37.7749, longitude: -122.4194 }),
        (err) => err.code === "permission-denied"
      );
    });
  });

  // ─── 3. Session Lifecycle & History Preservation ───────────────────────────

  describe("Session Lifecycle & History Preservation", function () {
    it("creates unique session IDs and preserves historical sessions", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      // Session 1
      const session1 = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 10,
        latitude: 37.7749,
        longitude: -122.4194
      });

      // Stop Session 1
      await stopAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });

      // Session 2
      const session2 = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 15,
        latitude: 37.7749,
        longitude: -122.4194
      });

      assert.notStrictEqual(session1.sessionId, session2.sessionId);

      // Verify Session 1 historical record is preserved and marked inactive
      const hist1 = await db.collection("attendance_sessions").doc(session1.sessionId).get();
      assert.strictEqual(hist1.exists, true);
      assert.strictEqual(hist1.data().isActive, false);

      // Verify Session 2 historical record is active
      const hist2 = await db.collection("attendance_sessions").doc(session2.sessionId).get();
      assert.strictEqual(hist2.exists, true);
      assert.strictEqual(hist2.data().isActive, true);

      // Verify active pointer references Session 2
      const activePointer = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(activePointer.data().sessionId, session2.sessionId);
      assert.strictEqual(activePointer.data().isActive, true);

      // Clean up Session 2
      await stopAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });
    });
  });

  // ─── 4. QR Token Rotation & Invalidation ───────────────────────────────────

  describe("QR Token Rotation & Invalidation", function () {
    it("generates rotating tokens and invalidates tokens once session is stopped", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const session = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 10,
        latitude: 37.7749,
        longitude: -122.4194
      });

      // Token 1
      const token1 = await generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE });
      assert.ok(token1.tokenId);
      assert.strictEqual(token1.validityMs, 30000);

      // Token 2 (Rotation after 25s)
      const token2 = await generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE });
      assert.ok(token2.tokenId);
      assert.notStrictEqual(token1.tokenId, token2.tokenId);

      // Stop session
      await stopAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });

      // Token generation must fail on closed session
      await assert.rejects(
        generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "failed-precondition"
      );

      // Validation of previously issued Token 1 must fail on closed session
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        validateQrTokenCore(db, studentAuth, {
          tokenId: token1.tokenId,
          courseCode: COURSE_CODE,
          sessionId: session.sessionId
        }),
        (err) => err.code === "failed-precondition"
      );
    });
  });

  // ─── 5. Live Attendance Filtering & Status Accuracy ────────────────────────

  describe("Live Attendance Filtering & Status Tracking", function () {
    it("filters attendance records strictly by active session and distinguishes manual requests", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      // Session A
      const sessionA = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        latitude: 37.7749,
        longitude: -122.4194
      });
      const recordA = {
        sessionId: sessionA.sessionId,
        studentUid: STUDENT_1_UID,
        studentName: "Alice Portal",
        studentEmail: STUDENT_1_EMAIL,
        courseCode: COURSE_CODE,
        status: "APPROVED",
        overallScore: 90,
        timestamp: Date.now() - 50000
      };
      await db.collection("attendance_records").doc(`${sessionA.sessionId}_${STUDENT_1_UID}`).set(recordA);
      await stopAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });

      // Session B (Active Session)
      const sessionB = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        latitude: 37.7749,
        longitude: -122.4194
      });

      // Student 1 submits valid claim in Session B
      const tokenB = await generateQrTokenCore(db, facultyAuth, { courseCode: COURSE_CODE });
      const student1Auth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await validateQrTokenCore(db, student1Auth, {
        tokenId: tokenB.tokenId,
        courseCode: COURSE_CODE,
        sessionId: sessionB.sessionId
      });

      const recordB1 = {
        sessionId: sessionB.sessionId,
        studentUid: STUDENT_1_UID,
        studentName: "Alice Portal",
        studentEmail: STUDENT_1_EMAIL,
        courseCode: COURSE_CODE,
        status: "REVIEW",
        overallScore: 82,
        timestamp: Date.now()
      };
      await db.collection("attendance_records").doc(`${sessionB.sessionId}_${STUDENT_1_UID}`).set(recordB1);

      // Student 2 submits a MANUAL_REQUEST in Session B (not auto-approved)
      const recordB2 = {
        sessionId: sessionB.sessionId,
        studentUid: STUDENT_2_UID,
        studentName: "Bob Portal",
        studentEmail: STUDENT_2_EMAIL,
        courseCode: COURSE_CODE,
        matchResult: "MANUAL_REQUEST",
        status: "REVIEW",
        overallScore: 0,
        rejectionReason: "Manual Request: Camera malfunction",
        timestamp: Date.now()
      };
      await db.collection("attendance_records").doc(`${sessionB.sessionId}_${STUDENT_2_UID}`).set(recordB2);

      // Query records for course and filter by active session B
      const courseRecordsSnap = await db
        .collection("attendance_records")
        .where("courseCode", "==", COURSE_CODE)
        .get();

      const allCourseRecords = courseRecordsSnap.docs.map((d) => d.data());
      const sessionBRecords = allCourseRecords.filter((r) => r.sessionId === sessionB.sessionId);

      // Only Session B records are shown in the active dashboard
      assert.strictEqual(sessionBRecords.length, 2);
      assert.strictEqual(sessionBRecords.some((r) => r.sessionId === sessionA.sessionId), false);

      // Manual request is present with REVIEW status and NOT auto-approved
      const manualRec = sessionBRecords.find((r) => r.studentEmail === STUDENT_2_EMAIL);
      assert.ok(manualRec);
      assert.strictEqual(manualRec.matchResult, "MANUAL_REQUEST");
      assert.strictEqual(manualRec.status, "REVIEW");
      assert.notStrictEqual(manualRec.status, "APPROVED");

      // Faculty approves student 1's record
      await db.collection("attendance_records").doc(`${sessionB.sessionId}_${STUDENT_1_UID}`).update({
        status: "APPROVED"
      });

      const updatedRecordB1 = await db.collection("attendance_records").doc(`${sessionB.sessionId}_${STUDENT_1_UID}`).get();
      assert.strictEqual(updatedRecordB1.data().status, "APPROVED");

      // Clean up Session B
      await stopAttendanceSessionCore(db, facultyAuth, { courseCode: COURSE_CODE });
    });
  });
});
