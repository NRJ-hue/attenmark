const assert = require("assert");
const http = require("http");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
process.env.GCLOUD_PROJECT = "attendmark-c2f07";
process.env.NODE_ENV = "test";

if (!getApps().length) {
  initializeApp({ projectId: "attendmark-c2f07" });
}

const db = getFirestore();
const { classifyRoleFromEmail, bootstrapProfileCore } = require("../profile_service");
const app = require("../server");

describe("Profile Service & Hosted API Server Tests", function () {
  this.timeout(10000);

  // ─── 1. Role Classification Unit Tests ─────────────────────────────────────
  describe("classifyRoleFromEmail (Exact First-Two-Characters Rule)", function () {
    it("classifies emails starting with two digits (00-99) as student regardless of domain", function () {
      assert.strictEqual(classifyRoleFromEmail("00student@univ.edu"), "student");
      assert.strictEqual(classifyRoleFromEmail("21bcs042@college.ac.in"), "student");
      assert.strictEqual(classifyRoleFromEmail("99test@gmail.com"), "student");
      assert.strictEqual(classifyRoleFromEmail("05alex@outlook.com"), "student");
      assert.strictEqual(classifyRoleFromEmail("123456@school.org"), "student");
    });

    it("classifies emails starting with non-digits as faculty regardless of domain", function () {
      assert.strictEqual(classifyRoleFromEmail("faculty@test.com"), "faculty");
      assert.strictEqual(classifyRoleFromEmail("prof.smith@university.edu"), "faculty");
      assert.strictEqual(classifyRoleFromEmail("dean@college.ac.in"), "faculty");
      assert.strictEqual(classifyRoleFromEmail("student@test.com"), "faculty"); // Known consequence: starts with 'st'
      assert.strictEqual(classifyRoleFromEmail("ab@school.edu"), "faculty");
    });

    it("classifies emails with single digit or mixed prefix as faculty", function () {
      assert.strictEqual(classifyRoleFromEmail("1astudent@college.edu"), "faculty");
      assert.strictEqual(classifyRoleFromEmail("a1student@college.edu"), "faculty");
      assert.strictEqual(classifyRoleFromEmail("9_user@college.edu"), "faculty");
    });

    it("safely handles edge cases (short, empty, non-string) defaulting to faculty", function () {
      assert.strictEqual(classifyRoleFromEmail(""), "faculty");
      assert.strictEqual(classifyRoleFromEmail("1"), "faculty");
      assert.strictEqual(classifyRoleFromEmail(null), "faculty");
      assert.strictEqual(classifyRoleFromEmail(undefined), "faculty");
    });
  });

  // ─── 2. bootstrapProfileCore Tests ─────────────────────────────────────────
  describe("bootstrapProfileCore (Server-Side Enforcement)", function () {
    const STUDENT_UID = "test-stu-profile-" + Date.now();
    const STUDENT_EMAIL = "22bcs101@college.edu";

    const FACULTY_UID = "test-fac-profile-" + Date.now();
    const FACULTY_EMAIL = "prof.jones@college.edu";

    it("rejects unauthenticated caller without UID", async function () {
      await assert.rejects(
        async () => bootstrapProfileCore(db, null, {}),
        /User must be authenticated/
      );
      await assert.rejects(
        async () => bootstrapProfileCore(db, { uid: "" }, {}),
        /User must be authenticated/
      );
    });

    it("rejects caller without email in auth context", async function () {
      await assert.rejects(
        async () => bootstrapProfileCore(db, { uid: "user-no-email", email: "" }, {}),
        /valid email address/
      );
    });

    it("creates missing profile for student email (starts with digits) and ignores client-supplied role", async function () {
      const auth = { uid: STUDENT_UID, email: STUDENT_EMAIL };
      // Malicious client tries to claim "faculty"
      const res = await bootstrapProfileCore(db, auth, {
        role: "faculty",
        name: "Alice Student"
      });

      assert.strictEqual(res.uid, STUDENT_UID);
      assert.strictEqual(res.email, STUDENT_EMAIL);
      assert.strictEqual(res.role, "student", "Client cannot override role to faculty");
      assert.strictEqual(res.name, "Alice Student");
      assert.strictEqual(res.isNew, true);

      // Verify stored in Firestore
      const doc = await db.collection("users").doc(STUDENT_UID).get();
      assert.ok(doc.exists);
      assert.strictEqual(doc.data().role, "student");
      assert.strictEqual(doc.data().email, STUDENT_EMAIL);
    });

    it("preserves an existing profile on repeated login without overwriting existing fields", async function () {
      const auth = { uid: STUDENT_UID, email: STUDENT_EMAIL };

      // Manually set an extra field on the profile in Firestore
      await db.collection("users").doc(STUDENT_UID).update({
        faceEnrolled: true,
        enrolledAt: 123456789
      });

      // Second login attempt
      const res = await bootstrapProfileCore(db, auth, {
        name: "Different Name",
        role: "admin"
      });

      assert.strictEqual(res.isNew, false);
      assert.strictEqual(res.role, "student");

      const doc = await db.collection("users").doc(STUDENT_UID).get();
      assert.strictEqual(doc.data().faceEnrolled, true, "Existing custom fields must be preserved");
      assert.strictEqual(doc.data().enrolledAt, 123456789);
    });

    it("creates missing profile for faculty email (starts with non-digits)", async function () {
      const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const res = await bootstrapProfileCore(db, auth, {
        name: "Dr. Indiana Jones"
      });

      assert.strictEqual(res.uid, FACULTY_UID);
      assert.strictEqual(res.email, FACULTY_EMAIL);
      assert.strictEqual(res.role, "faculty");
      assert.strictEqual(res.isNew, true);

      const doc = await db.collection("users").doc(FACULTY_UID).get();
      assert.ok(doc.exists);
      assert.strictEqual(doc.data().role, "faculty");
    });
  });

  // ─── 3. Express HTTP Server Integration Tests ─────────────────────────────
  describe("Express API Endpoints via HTTP", function () {
    let server;
    let baseUrl;

    before(function (done) {
      server = http.createServer(app);
      server.listen(0, "127.0.0.1", () => {
        const port = server.address().port;
        baseUrl = `http://127.0.0.1:${port}`;
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

    it("GET /health returns 200 with service status and reveals no secrets", async function () {
      const res = await fetch(`${baseUrl}/health`);
      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.status, "healthy");
      assert.strictEqual(json.projectId, "attendmark-c2f07");
      assert.ok(json.timestamp);
      assert.strictEqual(json.privateKey, undefined);
      assert.strictEqual(json.secret, undefined);
    });

    it("POST /api/bootstrap-profile rejects unauthenticated request with 401", async function () {
      const res = await fetch(`${baseUrl}/api/bootstrap-profile`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: "Hacker" })
      });
      assert.strictEqual(res.status, 401);
      const json = await res.json();
      assert.strictEqual(json.error.code, "unauthenticated");
    });

    it("POST /api/bootstrap-profile succeeds with authenticated student token", async function () {
      const testUid = "http-stu-" + Date.now();
      const testEmail = "24cs999@univ.edu";

      const res = await fetch(`${baseUrl}/api/bootstrap-profile`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock_token_ignored_in_test_mode",
          "x-test-auth-uid": testUid,
          "x-test-auth-email": testEmail
        },
        body: JSON.stringify({ name: "Bob Student" })
      });

      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.role, "student");
      assert.strictEqual(json.email, testEmail);
      assert.strictEqual(json.data.role, "student"); // callable compatibility
    });

    it("POST /api/bootstrap-profile succeeds with authenticated faculty token", async function () {
      const testUid = "http-fac-" + Date.now();
      const testEmail = "prof.clark@univ.edu";

      const res = await fetch(`${baseUrl}/api/bootstrap-profile`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock_token_ignored_in_test_mode",
          "x-test-auth-uid": testUid,
          "x-test-auth-email": testEmail
        },
        body: JSON.stringify({ name: "Prof Clark" })
      });

      assert.strictEqual(res.status, 200);
      const json = await res.json();
      assert.strictEqual(json.role, "faculty");
      assert.strictEqual(json.email, testEmail);
    });

    it("POST /api/startAttendanceSession rejects student with 403 permission-denied", async function () {
      const studentUid = "http-stu-deny-" + Date.now();
      const studentEmail = "23bcs555@univ.edu";

      // Bootstrap student profile first
      await bootstrapProfileCore(db, { uid: studentUid, email: studentEmail }, {});

      const res = await fetch(`${baseUrl}/api/startAttendanceSession`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer mock_token",
          "x-test-auth-uid": studentUid,
          "x-test-auth-email": studentEmail
        },
        body: JSON.stringify({
          courseCode: "TEST01",
          latitude: 37.7749,
          longitude: -122.4194
        })
      });

      assert.strictEqual(res.status, 403);
      const json = await res.json();
      assert.strictEqual(json.error.code, "permission-denied");
    });
  });
});
