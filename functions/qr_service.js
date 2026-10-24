const crypto = require("crypto");
const config = require("./config");

class ServiceError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/**
 * Server-side core implementation of generateQrToken.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ courseCode: string, sessionId?: string }} data
 */
async function generateQrTokenCore(db, authContext, data) {
  // 1. Authentication Check
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  // 2. Validate input
  if (!data || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing courseCode in request.");
  }

  const { courseCode } = data;

  // 3. Verify caller's role is faculty
  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "faculty") {
    throw new ServiceError("permission-denied", "Only faculty members can generate QR tokens.");
  }

  // 4. Read active session pointer in sessions/{courseCode}
  const sessionDoc = await db.collection("sessions").doc(courseCode).get();
  if (!sessionDoc.exists) {
    throw new ServiceError("not-found", `No session record found for course ${courseCode}.`);
  }

  const sessionData = sessionDoc.data();

  // 5. Verify session is active
  if (sessionData.isActive !== true) {
    throw new ServiceError("failed-precondition", "Session is inactive.");
  }

  // 6. Verify session has not expired
  const now = Date.now();
  if ((sessionData.expiresAt || 0) <= now) {
    throw new ServiceError("failed-precondition", "Session has expired.");
  }

  // 7. Verify ownership: Caller must own this session
  const callerEmail = (authContext.email || userDoc.data().email || "").toLowerCase();
  const sessionFacultyEmail = (sessionData.facultyEmail || "").toLowerCase();
  if (callerEmail !== sessionFacultyEmail) {
    throw new ServiceError("permission-denied", "You are not authorized to manage this course session.");
  }

  // 8. Resolve authoritative unique sessionId
  const sessionId = sessionData.sessionId;
  if (!sessionId) {
    throw new ServiceError("failed-precondition", "Active session is missing an authoritative sessionId.");
  }

  // If client provided a sessionId, it must match the active session
  if (data.sessionId && data.sessionId !== sessionId) {
    throw new ServiceError("invalid-argument", "Requested sessionId does not match the active course session.");
  }

  // 9. Generate cryptographically secure, opaque token
  const tokenId = crypto.randomBytes(32).toString("hex");
  const issuedAt = Date.now();
  const expiresAt = issuedAt + config.TOKEN_VALIDITY_MS;

  // 10. Store token in qr_tokens/{tokenId}
  const tokenRecord = {
    tokenId,
    sessionId,
    courseCode,
    facultyUid: authContext.uid,
    facultyEmail: sessionFacultyEmail,
    issuedAt,
    expiresAt
  };

  await db.collection("qr_tokens").doc(tokenId).set(tokenRecord);

  return {
    tokenId,
    expiresAt,
    validityMs: config.TOKEN_VALIDITY_MS,
    refreshIntervalMs: config.QR_REFRESH_INTERVAL_MS
  };
}

/**
 * Server-side core implementation of validateQrToken.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ tokenId: string, courseCode: string, sessionId: string }} data
 */
async function validateQrTokenCore(db, authContext, data) {
  // 1. Authentication Check
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  // 2. Validate input parameters
  if (!data || !data.tokenId || !data.courseCode || !data.sessionId) {
    throw new ServiceError("invalid-argument", "Missing required fields: tokenId, courseCode, and sessionId are required.");
  }

  const { tokenId, courseCode, sessionId } = data;

  // 3. Verify student role
  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "student") {
    throw new ServiceError("permission-denied", "Only students can validate QR attendance tokens.");
  }

  const studentEmail = (authContext.email || userDoc.data().email || "").toLowerCase();

  // 4. Course eligibility check: Student must be enrolled in course
  const coursesSnapshot = await db.collection("courses").where("classCode", "==", courseCode).get();
  if (coursesSnapshot.empty) {
    throw new ServiceError("not-found", `No course found matching code ${courseCode}.`);
  }

  const courseDoc = coursesSnapshot.docs[0];
  const enrolledStudents = (courseDoc.data().enrolledStudents || []).map((e) => (e || "").toLowerCase());
  if (!enrolledStudents.includes(studentEmail)) {
    throw new ServiceError("permission-denied", "Student is not enrolled in this course.");
  }

  // 5. Execute atomic validation and claim inside a Firestore transaction
  const result = await db.runTransaction(async (transaction) => {
    // A. Read QR token document
    const tokenRef = db.collection("qr_tokens").doc(tokenId);
    const tokenDoc = await transaction.get(tokenRef);

    if (!tokenDoc.exists) {
      throw new ServiceError("not-found", "Invalid or unknown QR token.");
    }

    const tokenData = tokenDoc.data();

    // Verify course matches
    if (tokenData.courseCode !== courseCode) {
      throw new ServiceError("invalid-argument", "QR token belongs to a different course.");
    }

    // Verify session matches
    if (tokenData.sessionId !== sessionId) {
      throw new ServiceError("invalid-argument", "QR token belongs to a different session.");
    }

    // Verify token expiry using server time
    const now = Date.now();
    if ((tokenData.expiresAt || 0) <= now) {
      throw new ServiceError("deadline-exceeded", "QR token has expired.");
    }

    // B. Verify the active session pointer is still active and valid
    const sessionRef = db.collection("sessions").doc(courseCode);
    const sessionDoc = await transaction.get(sessionRef);

    if (!sessionDoc.exists || sessionDoc.data().isActive !== true) {
      throw new ServiceError("failed-precondition", "Attendance session is no longer active.");
    }

    if ((sessionDoc.data().expiresAt || 0) <= now) {
      throw new ServiceError("failed-precondition", "Attendance session has expired.");
    }

    if (sessionDoc.data().sessionId !== sessionId) {
      throw new ServiceError("invalid-argument", "Session mismatch: active session has changed.");
    }

    // C. Verify final attendance record has not already been submitted
    const finalRecordId = `${sessionId}_${authContext.uid}`;
    const recordRef = db.collection("attendance_records").doc(finalRecordId);
    const recordDoc = await transaction.get(recordRef);

    if (recordDoc.exists) {
      throw new ServiceError("already-exists", "Attendance has already been recorded for this session.");
    }

    // D. Read attendance claim document
    const claimRef = db.collection("attendance_claims").doc(finalRecordId);
    const claimDoc = await transaction.get(claimRef);

    if (!claimDoc.exists) {
      // First attempt for this student in this session
      const newClaim = {
        sessionId,
        studentUid: authContext.uid,
        studentEmail,
        courseCode,
        tokenId,
        status: "CLAIMED",
        attemptCount: 1,
        maxAttempts: config.MAX_ATTEMPTS,
        claimedAt: now,
        updatedAt: now
      };

      transaction.set(claimRef, newClaim);

      return {
        valid: true,
        sessionId,
        courseCode,
        attempt: 1,
        retryRemaining: config.MAX_ATTEMPTS - 1
      };
    } else {
      // Existing claim: Evaluate single permitted retry
      const claimData = claimDoc.data();

      if (claimData.status === "SUBMITTED" || claimData.status === "COMPLETED") {
        throw new ServiceError("already-exists", "Attendance has already been recorded for this session.");
      }

      if (claimData.attemptCount >= claimData.maxAttempts) {
        throw new ServiceError("resource-exhausted", `Maximum verification attempts (${claimData.maxAttempts}) reached for this session.`);
      }

      // Increment attempt count for the single permitted retry
      const newAttemptCount = claimData.attemptCount + 1;
      transaction.update(claimRef, {
        lastTokenId: tokenId,
        attemptCount: newAttemptCount,
        status: "RETRYING",
        updatedAt: now
      });

      return {
        valid: true,
        sessionId,
        courseCode,
        attempt: newAttemptCount,
        retryRemaining: claimData.maxAttempts - newAttemptCount
      };
    }
  });

  return result;
}

module.exports = {
  ServiceError,
  generateQrTokenCore,
  validateQrTokenCore
};
