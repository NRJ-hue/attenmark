const assert = require("assert");
const http = require("http");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

const {
  leaveCourseCore,
  archiveCourseCore,
  unarchiveCourseCore
} = require("../course_service");
const { startAttendanceSessionCore } = require("../qr_service");
const app = require("../server");

describe("Course Service & Archive/Leave Functionality", function () {
  this.timeout(15000);

  let db;
  const COURSE_CODE = "ARCH101";
  const FACULTY_UID = "fac-archive-owner";
  const FACULTY_EMAIL = "prof.archive@college.edu";
  const OTHER_FACULTY_UID = "fac-other";
  const OTHER_FACULTY_EMAIL = "prof.other@college.edu";
  const STUDENT_1_UID = "21student1-uid";
  const STUDENT_1_EMAIL = "21student1@college.edu";
  const STUDENT_2_UID = "21student2-uid";
  const STUDENT_2_EMAIL = "21student2@college.edu";
  const STRANGER_STUDENT_UID = "21stranger-uid";
  const STRANGER_STUDENT_EMAIL = "21stranger@college.edu";

  before(async function () {
    process.env.NODE_ENV = "test";
    process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
    if (!getApps().length) {
      initializeApp({ projectId: "attendmark-course-test" });
    }
    db = getFirestore();
  });

  beforeEach(async function () {
    // Setup users
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty",
      email: FACULTY_EMAIL,
      name: "Prof. Archive"
    });

    await db.collection("users").doc(OTHER_FACULTY_UID).set({
      role: "faculty",
      email: OTHER_FACULTY_EMAIL,
      name: "Prof. Other"
    });

    await db.collection("users").doc(STUDENT_1_UID).set({
      role: "student",
      email: STUDENT_1_EMAIL,
      name: "Student One"
    });

    await db.collection("users").doc(STUDENT_2_UID).set({
      role: "student",
      email: STUDENT_2_EMAIL,
      name: "Student Two"
    });

    await db.collection("users").doc(STRANGER_STUDENT_UID).set({
      role: "student",
      email: STRANGER_STUDENT_EMAIL,
      name: "Stranger Student"
    });

    // Setup base course
    await db.collection("courses").doc(`course-${COURSE_CODE}`).set({
      name: "Architecture 101",
      classCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      enrolledStudents: [STUDENT_1_EMAIL, STUDENT_2_EMAIL],
      isArchived: false
    });
  });

  describe("leaveCourseCore", function () {
    it("rejects unauthenticated caller", async function () {
      await assert.rejects(
        leaveCourseCore(db, null, { courseCode: COURSE_CODE }),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects caller with faculty role with permission-denied", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      await assert.rejects(
        leaveCourseCore(db, facultyAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects when course does not exist", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        leaveCourseCore(db, studentAuth, { courseCode: "NONEXISTENT" }),
        (err) => err.code === "not-found"
      );
    });

    it("rejects when student is not enrolled in the course", async function () {
      const strangerAuth = { uid: STRANGER_STUDENT_UID, email: STRANGER_STUDENT_EMAIL };
      await assert.rejects(
        leaveCourseCore(db, strangerAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "failed-precondition" && err.message.includes("not enrolled")
      );
    });

    it("rejects when course is archived", async function () {
      await db.collection("courses").doc(`course-${COURSE_CODE}`).update({ isArchived: true });
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        leaveCourseCore(db, studentAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "failed-precondition" && err.message.includes("archived")
      );
    });

    it("ALLOWS enrolled student to leave and removes strictly caller's email", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      const res = await leaveCourseCore(db, studentAuth, { courseCode: COURSE_CODE });

      assert.strictEqual(res.success, true);
      assert.strictEqual(res.courseCode, COURSE_CODE);
      assert.strictEqual(res.studentEmail, STUDENT_1_EMAIL);

      // Verify Firestore state
      const courseDoc = await db.collection("courses").doc(`course-${COURSE_CODE}`).get();
      const enrolled = courseDoc.data().enrolledStudents;
      assert.strictEqual(enrolled.includes(STUDENT_1_EMAIL), false, "Student 1 must be removed");
      assert.strictEqual(enrolled.includes(STUDENT_2_EMAIL), true, "Student 2 must remain enrolled");
      assert.strictEqual(enrolled.length, 1);
    });
  });

  describe("archiveCourseCore & unarchiveCourseCore", function () {
    it("rejects unauthenticated caller from archiving", async function () {
      await assert.rejects(
        archiveCourseCore(db, null, { courseCode: COURSE_CODE }),
        (err) => err.code === "unauthenticated"
      );
    });

    it("rejects student from archiving a course with permission-denied", async function () {
      const studentAuth = { uid: STUDENT_1_UID, email: STUDENT_1_EMAIL };
      await assert.rejects(
        archiveCourseCore(db, studentAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("rejects unauthorized faculty from archiving another faculty's course", async function () {
      const otherAuth = { uid: OTHER_FACULTY_UID, email: OTHER_FACULTY_EMAIL };
      await assert.rejects(
        archiveCourseCore(db, otherAuth, { courseCode: COURSE_CODE }),
        (err) => err.code === "permission-denied"
      );
    });

    it("ALLOWS owning faculty to archive course and automatically stops any active session", async function () {
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      // Start an active session first
      const sessionRes = await startAttendanceSessionCore(db, facultyAuth, {
        courseCode: COURSE_CODE,
        durationMinutes: 15,
        latitude: 37.7749,
        longitude: -122.4194
      });
      assert.ok(sessionRes.sessionId);

      // Verify session is active
      const sessionBefore = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(sessionBefore.data().isActive, true);

      // Archive the course
      const archiveRes = await archiveCourseCore(db, facultyAuth, { courseCode: COURSE_CODE });
      assert.strictEqual(archiveRes.success, true);
      assert.strictEqual(archiveRes.isArchived, true);

      // Verify course is marked archived in Firestore
      const courseDoc = await db.collection("courses").doc(`course-${COURSE_CODE}`).get();
      assert.strictEqual(courseDoc.data().isArchived, true);
      assert.ok(courseDoc.data().archivedAt);

      // Verify active session was automatically stopped
      const sessionAfter = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(sessionAfter.data().isActive, false, "Session must be deactivated upon course archiving");
    });

    it("rejects starting a new attendance session for an archived course", async function () {
      await db.collection("courses").doc(`course-${COURSE_CODE}`).update({ isArchived: true });
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      await assert.rejects(
        startAttendanceSessionCore(db, facultyAuth, {
          courseCode: COURSE_CODE,
          durationMinutes: 10,
          latitude: 37.7749,
          longitude: -122.4194
        }),
        (err) => err.code === "failed-precondition" && err.message.includes("archived")
      );
    });

    it("ALLOWS owning faculty to unarchive an archived course", async function () {
      await db.collection("courses").doc(`course-${COURSE_CODE}`).update({ isArchived: true });
      const facultyAuth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

      const unarchiveRes = await unarchiveCourseCore(db, facultyAuth, { courseCode: COURSE_CODE });
      assert.strictEqual(unarchiveRes.success, true);
      assert.strictEqual(unarchiveRes.isArchived, false);

      const courseDoc = await db.collection("courses").doc(`course-${COURSE_CODE}`).get();
      assert.strictEqual(courseDoc.data().isArchived, false);
    });
  });

  describe("HTTP API Endpoints", function () {
    let server;
    let baseUrl;

    before(function (done) {
      server = http.createServer(app);
      server.listen(0, () => {
        baseUrl = `http://127.0.0.1:${server.address().port}`;
        done();
      });
    });

    after(function (done) {
      if (server) {
        server.close(done);
      } else {
        done();
      }
    });

    it("POST /api/leaveCourse removes student caller", async function () {
      const res = await fetch(`${baseUrl}/api/leaveCourse`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock-token",
          "x-test-auth-uid": STUDENT_1_UID,
          "x-test-auth-email": STUDENT_1_EMAIL
        },
        body: JSON.stringify({ courseCode: COURSE_CODE })
      });

      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.success, true);
    });

    it("POST /api/archiveCourse archives course for owning faculty", async function () {
      const res = await fetch(`${baseUrl}/api/archiveCourse`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock-token",
          "x-test-auth-uid": FACULTY_UID,
          "x-test-auth-email": FACULTY_EMAIL
        },
        body: JSON.stringify({ courseCode: COURSE_CODE })
      });

      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.success, true);
      assert.strictEqual(json.isArchived, true);
    });

    it("POST /api/unarchiveCourse unarchives course for owning faculty", async function () {
      const res = await fetch(`${baseUrl}/api/unarchiveCourse`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock-token",
          "x-test-auth-uid": FACULTY_UID,
          "x-test-auth-email": FACULTY_EMAIL
        },
        body: JSON.stringify({ courseCode: COURSE_CODE })
      });

      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.success, true);
      assert.strictEqual(json.isArchived, false);
    });
  });
});
