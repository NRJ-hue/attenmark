const assert = require("assert");
const fs = require("fs");
const path = require("path");
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds
} = require("@firebase/rules-unit-testing");
const { doc, getDoc, setDoc, updateDoc, writeBatch } = require("firebase/firestore");

describe("Phase 3: Firestore Security Rules (Live Emulator Enforcement)", function () {
  this.timeout(15000);

  let testEnv;
  const PROJECT_ID = "attendmark-c2f07";

  before(async function () {
    const rules = fs.readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8");
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        host: "127.0.0.1",
        port: 8080,
        rules: rules
      }
    });
  });

  after(async function () {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  beforeEach(async function () {
    await testEnv.clearFirestore();

    // Setup base data via admin context (bypasses security rules)
    await testEnv.withSecurityRulesDisabled(async (context) => {
      const adminDb = context.firestore();

      // Faculty user doc
      await setDoc(doc(adminDb, "users/faculty-uid"), {
        role: "faculty",
        email: "faculty@test.com",
        name: "Prof. Smith"
      });

      // Student user docs
      await setDoc(doc(adminDb, "users/student-uid-1"), {
        role: "student",
        email: "student1@test.com",
        name: "Alice"
      });

      await setDoc(doc(adminDb, "users/student-uid-2"), {
        role: "student",
        email: "student2@test.com",
        name: "Bob"
      });

      // Course
      await setDoc(doc(adminDb, "courses/course-cs101"), {
        name: "Computer Science",
        classCode: "CS101",
        facultyEmail: "faculty@test.com",
        enrolledStudents: ["student1@test.com", "student2@test.com"]
      });
    });
  });

  // ─── 1. Role Escalation & User Profile Rules ───────────────────────────────

  describe("users/{uid} rules", function () {
    it("allows a new user to self-register strictly as a student", async function () {
      const newStudentCtx = testEnv.authenticatedContext("new-student-uid", {
        email: "newstudent@test.com"
      });
      const studentDb = newStudentCtx.firestore();

      await assertSucceeds(
        setDoc(doc(studentDb, "users/new-student-uid"), {
          role: "student",
          email: "newstudent@test.com",
          name: "New Student"
        })
      );
    });

    it("DENIES user attempting to self-assign faculty role (privilege escalation)", async function () {
      const attackerCtx = testEnv.authenticatedContext("attacker-uid", {
        email: "attacker@test.com"
      });
      const attackerDb = attackerCtx.firestore();

      await assertFails(
        setDoc(doc(attackerDb, "users/attacker-uid"), {
          role: "faculty",
          email: "attacker@test.com",
          name: "Fake Faculty"
        })
      );
    });

    it("DENIES user attempting to modify their assigned role", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertFails(
        updateDoc(doc(studentDb, "users/student-uid-1"), {
          role: "faculty"
        })
      );
    });
  });

  // ─── 2. Backend Collections Protection ─────────────────────────────────────

  describe("backend collections client lockdown (sessions, attendance_sessions, qr_tokens, attendance_claims)", function () {
    it("DENIES any client from directly creating or updating active session pointers in sessions", async function () {
      const facultyCtx = testEnv.authenticatedContext("faculty-uid", {
        email: "faculty@test.com"
      });
      const facultyDb = facultyCtx.firestore();

      await assertFails(
        setDoc(doc(facultyDb, "sessions/CS101"), {
          sessionId: "client-direct-session",
          courseCode: "CS101",
          facultyEmail: "faculty@test.com",
          isActive: true
        })
      );
    });

    it("DENIES any client from directly creating or updating historical sessions in attendance_sessions", async function () {
      const facultyCtx = testEnv.authenticatedContext("faculty-uid", {
        email: "faculty@test.com"
      });
      const facultyDb = facultyCtx.firestore();

      await assertFails(
        setDoc(doc(facultyDb, "attendance_sessions/direct-hist-session"), {
          sessionId: "direct-hist-session",
          courseCode: "CS101",
          facultyEmail: "faculty@test.com",
          isActive: true
        })
      );
    });

    it("DENIES any client from directly creating or tampering with qr_tokens", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertFails(
        setDoc(doc(studentDb, "qr_tokens/fake-token"), {
          tokenId: "fake-token",
          sessionId: "session-123",
          courseCode: "CS101",
          expiresAt: Date.now() + 30000
        })
      );
    });

    it("DENIES any client from directly forging attendance_claims documents", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertFails(
        setDoc(doc(studentDb, "attendance_claims/session-123_student-uid-1"), {
          sessionId: "session-123",
          studentUid: "student-uid-1",
          status: "CLAIMED"
        })
      );
    });
  });

  // ─── 3. Attendance Records Security Rules ──────────────────────────────────

  describe("attendance_records/{recordId} rules", function () {
    const sessionId = "session-uuid-test";
    const validClaimDocId = `${sessionId}_student-uid-1`;

    it("DENIES unauthenticated write to attendance_records", async function () {
      const unauthDb = testEnv.unauthenticatedContext().firestore();

      await assertFails(
        setDoc(doc(unauthDb, "attendance_records/unauth_rec"), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW"
        })
      );
    });

    it("DENIES fabricated attendance write when no attendance_claims document exists", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Student tries to write attendance_records directly without scanning QR
      await assertFails(
        setDoc(doc(studentDb, `attendance_records/${sessionId}_student-uid-1`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          timestamp: Date.now()
        })
      );
    });

    it("DENIES student attempting to bypass QR verification in attendance_records using matchResult=MANUAL_REQUEST without an attendance_claim", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Attacker attempts to bypass claim verification in verified attendance_records collection
      await assertFails(
        setDoc(doc(studentDb, `attendance_records/${sessionId}_student-uid-1`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          overallScore: 0,
          rejectionReason: "Bypass attempt without claim",
          timestamp: Date.now()
        })
      );
    });

    it("DENIES student attempting to directly claim APPROVED status", async function () {
      // Setup backend attendance claim first
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_claims/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "CLAIMED"
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Student tries to submit directly as APPROVED -> Must be denied
      await assertFails(
        setDoc(doc(studentDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "APPROVED",
          timestamp: Date.now()
        })
      );
    });

    it("DENIES student attempting cross-student submission (mismatched UID)", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertFails(
        setDoc(doc(studentDb, `attendance_records/${sessionId}_student-uid-2`), {
          sessionId: sessionId,
          studentUid: "student-uid-2", // Spoofed student 2
          studentEmail: "student2@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          timestamp: Date.now()
        })
      );
    });

    it("ALLOWS legitimate student submission when backed by valid attendance claim", async function () {
      // Backend validates QR and creates claim doc
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_claims/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "CLAIMED"
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Submit valid record with REVIEW status
      await assertSucceeds(
        setDoc(doc(studentDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          overallScore: 85,
          timestamp: Date.now()
        })
      );
    });

    it("DENIES student attempting to overwrite or duplicate an already submitted record", async function () {
      // Pre-populate legitimate record
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_claims/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "CLAIMED"
        });
        await setDoc(doc(adminDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          timestamp: Date.now()
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Student tries to rewrite/duplicate document -> Treated as update -> Denied
      await assertFails(
        setDoc(doc(studentDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          timestamp: Date.now() + 5000
        })
      );
    });

    it("DENIES student from reading another student's attendance record", async function () {
      // Create student 2 record
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_records/${sessionId}_student-uid-2`), {
          sessionId: sessionId,
          studentUid: "student-uid-2",
          studentEmail: "student2@test.com",
          status: "REVIEW"
        });
      });

      const student1Ctx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const student1Db = student1Ctx.firestore();

      await assertFails(
        getDoc(doc(student1Db, `attendance_records/${sessionId}_student-uid-2`))
      );
    });

    it("DENIES student attempting to create record with non-matching document ID", async function () {
      // Backend validates QR and creates claim doc
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_claims/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "CLAIMED"
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Student tries to submit with a forged random document ID instead of ${sessionId}_${studentUid}
      await assertFails(
        setDoc(doc(studentDb, "attendance_records/random-duplicate-id-123"), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "REVIEW",
          overallScore: 85,
          timestamp: Date.now()
        })
      );
    });

    it("DENIES unauthorized Faculty B from approving or rejecting Faculty A's record", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        // Setup session owned by Faculty A (faculty@test.com)
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW"
        });
        // Setup Faculty B user profile
        await setDoc(doc(adminDb, "users/faculty-b-uid"), {
          role: "faculty",
          email: "facultyb@test.com",
          name: "Prof. Other"
        });
      });

      const facultyBCtx = testEnv.authenticatedContext("faculty-b-uid", {
        email: "facultyb@test.com"
      });
      const facultyBDb = facultyBCtx.firestore();

      // Faculty B attempts to approve Faculty A's record -> Must be DENIED
      await assertFails(
        updateDoc(doc(facultyBDb, `attendance_records/${validClaimDocId}`), {
          status: "APPROVED"
        })
      );

      // Faculty B attempts to reject Faculty A's record -> Must be DENIED
      await assertFails(
        updateDoc(doc(facultyBDb, `attendance_records/${validClaimDocId}`), {
          status: "REJECTED",
          rejectionReason: "Denied by unauthorized faculty"
        })
      );
    });

    it("ALLOWS authorized faculty to read and approve attendance record for their session", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW"
        });
      });

      const facultyCtx = testEnv.authenticatedContext("faculty-uid", {
        email: "faculty@test.com"
      });
      const facultyDb = facultyCtx.firestore();

      // Faculty can read
      await assertSucceeds(
        getDoc(doc(facultyDb, `attendance_records/${validClaimDocId}`))
      );

      // Authorized faculty can approve (update status)
      await assertSucceeds(
        updateDoc(doc(facultyDb, `attendance_records/${validClaimDocId}`), {
          status: "APPROVED"
        })
      );
    });

    it("DENIES student attempting to alter claimed courseCode (mismatched course)", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_claims/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "CLAIMED"
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      // Student attempts to submit for CS999 using a CS101 claim
      await assertFails(
        setDoc(doc(studentDb, `attendance_records/${validClaimDocId}`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS999",
          status: "REVIEW",
          overallScore: 85,
          timestamp: Date.now()
        })
      );
    });

    it("DENIES unauthorized Faculty B from directly creating an attendance record for Faculty A's session", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, "users/faculty-b-uid"), {
          role: "faculty",
          email: "facultyb@test.com",
          name: "Prof. Other"
        });
      });

      const facultyBCtx = testEnv.authenticatedContext("faculty-b-uid", {
        email: "facultyb@test.com"
      });
      const facultyBDb = facultyBCtx.firestore();

      // Faculty B tries to forge an approved attendance record for Faculty A's session
      await assertFails(
        setDoc(doc(facultyBDb, `attendance_records/${sessionId}_student-uid-1`), {
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "APPROVED",
          matchResult: "MANUAL_REQUEST_APPROVED",
          overallScore: 100,
          timestamp: Date.now()
        })
      );
    });
  });

  // ─── 4. Manual Attendance Requests Security Rules ──────────────────────────

  describe("manual_attendance_requests/{requestId} rules", function () {
    const sessionId = "session-manual-test";
    const manualDocId = `${sessionId}_student-uid-1`;

    it("ALLOWS student to submit an unverified manual request with status REVIEW and isQrVerified=false", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertSucceeds(
        setDoc(doc(studentDb, `manual_attendance_requests/${manualDocId}`), {
          id: manualDocId,
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          studentName: "Alice",
          courseCode: "CS101",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false,
          overallScore: 0,
          rejectionReason: "Camera broken, unable to scan QR",
          timestamp: Date.now()
        })
      );
    });

    it("DENIES student attempting to submit an APPROVED manual request", async function () {
      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertFails(
        setDoc(doc(studentDb, `manual_attendance_requests/${manualDocId}`), {
          id: manualDocId,
          sessionId: sessionId,
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          courseCode: "CS101",
          status: "APPROVED",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false,
          overallScore: 100,
          timestamp: Date.now()
        })
      );
    });

    it("DENIES student from updating or self-approving their manual request", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      await assertFails(
        updateDoc(doc(studentDb, `manual_attendance_requests/${manualDocId}`), {
          status: "APPROVED"
        })
      );
    });

    it("DENIES unauthorized Faculty B from approving Faculty A's manual request", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
        // Setup Faculty B
        await setDoc(doc(adminDb, "users/faculty-b-uid"), {
          role: "faculty",
          email: "faculty_b@test.com",
          name: "Prof. Other"
        });
      });

      const facultyBCtx = testEnv.authenticatedContext("faculty-b-uid", {
        email: "faculty_b@test.com"
      });
      const facultyBDb = facultyBCtx.firestore();

      await assertFails(
        updateDoc(doc(facultyBDb, `manual_attendance_requests/${manualDocId}`), {
          status: "APPROVED"
        })
      );
    });

    it("ALLOWS authorized faculty to resolve and approve a manual request", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
      });

      const facultyCtx = testEnv.authenticatedContext("faculty-uid", {
        email: "faculty@test.com"
      });
      const facultyDb = facultyCtx.firestore();

      await assertSucceeds(
        updateDoc(doc(facultyDb, `manual_attendance_requests/${manualDocId}`), {
          status: "APPROVED"
        })
      );
    });

    it("ALLOWS authorized faculty to atomically batch-approve manual request and create attendance record", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
      });

      const facultyCtx = testEnv.authenticatedContext("faculty-uid", {
        email: "faculty@test.com"
      });
      const facultyDb = facultyCtx.firestore();

      const batch = writeBatch(facultyDb);
      batch.update(doc(facultyDb, `manual_attendance_requests/${manualDocId}`), {
        status: "APPROVED"
      });
      batch.set(doc(facultyDb, `attendance_records/${manualDocId}`), {
        sessionId: sessionId,
        courseCode: "CS101",
        studentUid: "student-uid-1",
        studentEmail: "student1@test.com",
        studentName: "Alice",
        status: "APPROVED",
        matchResult: "MANUAL_REQUEST_APPROVED",
        overallScore: 100,
        timestamp: Date.now(),
        rejectionReason: null
      });

      await assertSucceeds(batch.commit());

      // Verify both documents exist with APPROVED status
      const manualSnap = await getDoc(doc(facultyDb, `manual_attendance_requests/${manualDocId}`));
      assert.strictEqual(manualSnap.data().status, "APPROVED");

      const recordSnap = await getDoc(doc(facultyDb, `attendance_records/${manualDocId}`));
      assert.strictEqual(recordSnap.data().status, "APPROVED");
      assert.strictEqual(recordSnap.data().matchResult, "MANUAL_REQUEST_APPROVED");
    });

    it("DENIES unauthorized Faculty B from atomically batch-approving Faculty A's manual request", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
        await setDoc(doc(adminDb, "users/faculty-b-uid"), {
          role: "faculty",
          email: "faculty_b@test.com",
          name: "Prof. Other"
        });
      });

      const facultyBCtx = testEnv.authenticatedContext("faculty-b-uid", {
        email: "faculty_b@test.com"
      });
      const facultyBDb = facultyBCtx.firestore();

      const batch = writeBatch(facultyBDb);
      batch.update(doc(facultyBDb, `manual_attendance_requests/${manualDocId}`), {
        status: "APPROVED"
      });
      batch.set(doc(facultyBDb, `attendance_records/${manualDocId}`), {
        sessionId: sessionId,
        courseCode: "CS101",
        studentUid: "student-uid-1",
        studentEmail: "student1@test.com",
        studentName: "Alice",
        status: "APPROVED",
        matchResult: "MANUAL_REQUEST_APPROVED",
        overallScore: 100,
        timestamp: Date.now(),
        rejectionReason: null
      });

      await assertFails(batch.commit());
    });

    it("DENIES student from batch-approving their manual request and attendance record", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
      });

      const studentCtx = testEnv.authenticatedContext("student-uid-1", {
        email: "student1@test.com"
      });
      const studentDb = studentCtx.firestore();

      const batch = writeBatch(studentDb);
      batch.update(doc(studentDb, `manual_attendance_requests/${manualDocId}`), {
        status: "APPROVED"
      });
      batch.set(doc(studentDb, `attendance_records/${manualDocId}`), {
        sessionId: sessionId,
        courseCode: "CS101",
        studentUid: "student-uid-1",
        studentEmail: "student1@test.com",
        studentName: "Alice",
        status: "APPROVED",
        matchResult: "MANUAL_REQUEST_APPROVED",
        overallScore: 100,
        timestamp: Date.now(),
        rejectionReason: null
      });

      await assertFails(batch.commit());
    });

    it("handles repeated batch approval safely and idempotently without duplicate records", async function () {
      await testEnv.withSecurityRulesDisabled(async (context) => {
        const adminDb = context.firestore();
        await setDoc(doc(adminDb, `attendance_sessions/${sessionId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          facultyEmail: "faculty@test.com"
        });
        await setDoc(doc(adminDb, `manual_attendance_requests/${manualDocId}`), {
          sessionId: sessionId,
          courseCode: "CS101",
          studentUid: "student-uid-1",
          studentEmail: "student1@test.com",
          status: "REVIEW",
          matchResult: "MANUAL_REQUEST",
          isQrVerified: false
        });
      });

      const facultyCtx = testEnv.authenticatedContext("faculty-uid", {
        email: "faculty@test.com"
      });
      const facultyDb = facultyCtx.firestore();

      // First batch approval
      const batch1 = writeBatch(facultyDb);
      batch1.update(doc(facultyDb, `manual_attendance_requests/${manualDocId}`), {
        status: "APPROVED"
      });
      batch1.set(doc(facultyDb, `attendance_records/${manualDocId}`), {
        sessionId: sessionId,
        courseCode: "CS101",
        studentUid: "student-uid-1",
        studentEmail: "student1@test.com",
        studentName: "Alice",
        status: "APPROVED",
        matchResult: "MANUAL_REQUEST_APPROVED",
        overallScore: 100,
        timestamp: Date.now(),
        rejectionReason: null
      });
      await assertSucceeds(batch1.commit());

      // Second batch approval (repeated click / network retry)
      const batch2 = writeBatch(facultyDb);
      batch2.update(doc(facultyDb, `manual_attendance_requests/${manualDocId}`), {
        status: "APPROVED"
      });
      batch2.set(doc(facultyDb, `attendance_records/${manualDocId}`), {
        sessionId: sessionId,
        courseCode: "CS101",
        studentUid: "student-uid-1",
        studentEmail: "student1@test.com",
        studentName: "Alice",
        status: "APPROVED",
        matchResult: "MANUAL_REQUEST_APPROVED",
        overallScore: 100,
        timestamp: Date.now(),
        rejectionReason: null
      });
      await assertSucceeds(batch2.commit());

      // Verify still exactly one record with deterministic ID
      const recordSnap = await getDoc(doc(facultyDb, `attendance_records/${manualDocId}`));
      assert.strictEqual(recordSnap.data().status, "APPROVED");
    });
  });
});
