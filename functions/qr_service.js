const crypto = require("crypto");
const config = require("./config");

class ServiceError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/**
 * Server-side core implementation of startAttendanceSession.
 * Atomically creates a unique historical session and sets the active pointer.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ courseCode: string, durationMinutes?: number, latitude?: number, longitude?: number }} data
 */
async function startAttendanceSessionCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing required field: courseCode.");
  }

  const { courseCode } = data;
  const durationMinutes = Number(data.durationMinutes) > 0 ? Number(data.durationMinutes) : 10;

  // Strict classroom coordinate & location presence validation
  // Requires explicit location configuration and validates geographic bounds
  const rawLat = data.location && data.location.latitude !== undefined ? data.location.latitude : data.latitude;
  const rawLon = data.location && data.location.longitude !== undefined ? data.location.longitude : data.longitude;

  if (rawLat === undefined || rawLat === null || rawLon === undefined || rawLon === null || rawLat === "" || rawLon === "") {
    throw new ServiceError("invalid-argument", "Classroom latitude and longitude are required to start an attendance session.");
  }

  const latitude = typeof rawLat === "number" ? rawLat : Number(rawLat);
  const longitude = typeof rawLon === "number" ? rawLon : Number(rawLon);

  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    throw new ServiceError("invalid-argument", "Classroom coordinates must be valid numbers.");
  }

  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    throw new ServiceError("invalid-argument", "Classroom coordinates out of valid range (latitude [-90, 90], longitude [-180, 180]).");
  }

  // Reject unset coordinates / Null Island (0.0, 0.0)
  if (Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001) {
    throw new ServiceError("invalid-argument", "Classroom coordinates cannot be unset (0.0, 0.0).");
  }

  // Verify faculty role
  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "faculty") {
    throw new ServiceError("permission-denied", "Only faculty members can start attendance sessions.");
  }

  const facultyEmail = (authContext.email || userDoc.data().email || "").toLowerCase();

  // Verify course ownership
  const coursesSnapshot = await db.collection("courses").where("classCode", "==", courseCode).get();
  if (coursesSnapshot.empty) {
    throw new ServiceError("not-found", `Course with code ${courseCode} not found.`);
  }

  const courseDoc = coursesSnapshot.docs[0];
  const courseData = courseDoc.data();

  // Reject starting session if course is archived
  if (courseData.isArchived === true) {
    throw new ServiceError("failed-precondition", "Cannot start an attendance session for an archived course.");
  }

  const courseFacultyEmail = (courseData.facultyEmail || "").toLowerCase();
  if (courseFacultyEmail !== facultyEmail) {
    throw new ServiceError("permission-denied", "You are not authorized to start a session for this course.");
  }

  // Generate unique session ID
  const sessionId = crypto.randomUUID();
  const startedAt = Date.now();
  const expiresAt = startedAt + durationMinutes * 60 * 1000;

  // Determine geofence radius: exactly 100m in local demo / emulator mode, exactly 50m in production.
  // The effective radius is determined strictly by trusted server configuration.
  // Clients cannot request an enlarged (e.g. 999999m) or unexpectedly reduced (e.g. 1m) radius.
  const isDemoMode = Boolean(
    process.env.FUNCTIONS_EMULATOR === "true" ||
    process.env.FIRESTORE_EMULATOR_HOST ||
    process.env.NODE_ENV === "development" ||
    process.env.NODE_ENV === "test" ||
    config.isDemoMode
  );

  let geofenceRadiusMeters;
  if (!isDemoMode) {
    // In production, radius is strictly 50m. Clients cannot alter this value.
    geofenceRadiusMeters = config.PRODUCTION_GEOFENCE_RADIUS_METERS;
  } else {
    // In demo/test mode, default is strictly 100m.
    // Explicit production configuration (50m) is accepted for testing production mode,
    // but any client-supplied enlarged (e.g. 999999m) or arbitrary reduced (e.g. 1m) values
    // are rejected and fall back strictly to the trusted demo default (100m).
    const requested = (data && typeof data.geofenceRadiusMeters === "number")
      ? data.geofenceRadiusMeters
      : (data && data.location && typeof data.location.geofenceRadiusMeters === "number")
        ? data.location.geofenceRadiusMeters
        : null;

    if (requested === config.PRODUCTION_GEOFENCE_RADIUS_METERS) {
      geofenceRadiusMeters = config.PRODUCTION_GEOFENCE_RADIUS_METERS;
    } else {
      geofenceRadiusMeters = config.DEMO_GEOFENCE_RADIUS_METERS;
    }
  }

  const sessionPayload = {
    sessionId,
    courseCode,
    facultyEmail,
    latitude,
    longitude,
    geofenceRadiusMeters,
    locationConfigured: true,
    location: {
      latitude,
      longitude,
      geofenceRadiusMeters,
      configuredAt: startedAt
    },
    isActive: true,
    durationMinutes,
    startedAt,
    expiresAt,
    currentTokenId: null,
    lastTokenIssuedAt: null
  };

  const batch = db.batch();

  // 1. Immutable historical record in attendance_sessions/{sessionId}
  const historicalRef = db.collection("attendance_sessions").doc(sessionId);
  batch.set(historicalRef, sessionPayload);

  // 2. Active pointer in sessions/{courseCode}
  const activePointerRef = db.collection("sessions").doc(courseCode);
  batch.set(activePointerRef, sessionPayload);

  await batch.commit();

  return {
    sessionId,
    courseCode,
    startedAt,
    expiresAt,
    durationMinutes,
    latitude,
    longitude,
    geofenceRadiusMeters,
    location: {
      latitude,
      longitude,
      geofenceRadiusMeters,
      configuredAt: startedAt
    },
    locationConfigured: true
  };
}

/**
 * Server-side core implementation of stopAttendanceSession.
 * Atomically marks both the active session pointer and historical session as inactive.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ courseCode: string }} data
 */
async function stopAttendanceSessionCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing required field: courseCode.");
  }

  const { courseCode } = data;

  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "faculty") {
    throw new ServiceError("permission-denied", "Only faculty members can stop attendance sessions.");
  }

  const facultyEmail = (authContext.email || userDoc.data().email || "").toLowerCase();

  const sessionRef = db.collection("sessions").doc(courseCode);
  const sessionDoc = await sessionRef.get();

  if (!sessionDoc.exists) {
    throw new ServiceError("not-found", `No active session record found for course ${courseCode}.`);
  }

  const sessionData = sessionDoc.data();
  if ((sessionData.facultyEmail || "").toLowerCase() !== facultyEmail) {
    throw new ServiceError("permission-denied", "You are not authorized to stop this course session.");
  }

  const stoppedAt = Date.now();
  const sessionId = sessionData.sessionId;

  // Guard: the active session pointer must carry a valid sessionId so we can
  // mark the corresponding historical attendance_sessions record inactive too.
  // A missing sessionId would leave attendance_sessions/{sessionId}.isActive === true,
  // which would allow stale QR tokens to validate even after the session is
  // nominally stopped. Treat this as a data-consistency error rather than
  // silently reporting success with a partial update.
  if (!sessionId) {
    throw new ServiceError(
      "failed-precondition",
      `Session record for course ${courseCode} is missing an authoritative sessionId. ` +
      "The session data may be corrupt. Contact an administrator to resolve the inconsistency."
    );
  }

  const batch = db.batch();
  batch.update(sessionRef, {
    isActive: false,
    stoppedAt
  });

  const historicalRef = db.collection("attendance_sessions").doc(sessionId);
  batch.update(historicalRef, {
    isActive: false,
    stoppedAt
  });

  await batch.commit();

  return {
    success: true,
    courseCode,
    sessionId,
    stoppedAt
  };
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

  // 10. Store token in qr_tokens/{tokenId} and atomically update authoritative currentTokenId in session
  const tokenRecord = {
    tokenId,
    sessionId,
    courseCode,
    facultyUid: authContext.uid,
    facultyEmail: sessionFacultyEmail,
    issuedAt,
    expiresAt
  };

  const batch = db.batch();
  batch.set(db.collection("qr_tokens").doc(tokenId), tokenRecord);
  batch.update(db.collection("sessions").doc(courseCode), {
    currentTokenId: tokenId,
    lastTokenIssuedAt: issuedAt
  });
  batch.update(db.collection("attendance_sessions").doc(sessionId), {
    currentTokenId: tokenId,
    lastTokenIssuedAt: issuedAt
  });
  await batch.commit();

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

    // Strict validation: Verify token's sessionId matches the authoritative active session pointer
    if (sessionDoc.data().sessionId !== sessionId) {
      throw new ServiceError("invalid-argument", "Session mismatch: active session has changed.");
    }

    if (tokenData.sessionId !== sessionDoc.data().sessionId) {
      throw new ServiceError("invalid-argument", "QR token is from an old or inactive session.");
    }

    // Authoritative current-token verification: Once a replacement QR becomes active, previous QR is immediately rejected
    if (sessionDoc.data().currentTokenId && sessionDoc.data().currentTokenId !== tokenId) {
      throw new ServiceError("deadline-exceeded", "QR token has been rotated and is no longer valid. Please scan the current code on display.");
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
        retryRemaining: config.MAX_ATTEMPTS - 1,
        claimStatus: "CLAIMED"
      };
    } else {
      const claimData = claimDoc.data();

      if (claimData.status === "SUBMITTED" || claimData.status === "COMPLETED") {
        throw new ServiceError("already-exists", "Attendance has already been recorded for this session.");
      }

      if (claimData.status === "REJECTED_GPS") {
        throw new ServiceError("permission-denied", "Attendance rejected due to GPS location outside classroom. Retries are not permitted for location violations.");
      }

      // If previous attempt reached an eligible failure state, consume the single permitted retry
      if (claimData.status === "VERIFICATION_FAILED") {
        if (claimData.attemptCount >= claimData.maxAttempts) {
          throw new ServiceError("resource-exhausted", `Maximum verification attempts (${claimData.maxAttempts}) reached for this session.`);
        }

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
          retryRemaining: claimData.maxAttempts - newAttemptCount,
          claimStatus: "RETRYING"
        };
      }

      // If the claim is currently active and has NOT failed verification yet (e.g. duplicate camera frames, token rotated before capture)
      if (claimData.status === "CLAIMED" || claimData.status === "RETRYING") {
        if (claimData.attemptCount > claimData.maxAttempts) {
          throw new ServiceError("resource-exhausted", `Maximum verification attempts (${claimData.maxAttempts}) reached for this session.`);
        }

        // Return existing active claim state without consuming another attempt
        return {
          valid: true,
          sessionId,
          courseCode,
          attempt: claimData.attemptCount,
          retryRemaining: Math.max(0, claimData.maxAttempts - claimData.attemptCount),
          claimStatus: claimData.status
        };
      }

      if (claimData.attemptCount >= claimData.maxAttempts) {
        throw new ServiceError("resource-exhausted", `Maximum verification attempts (${claimData.maxAttempts}) reached for this session.`);
      }

      // Fallback for existing legacy claims
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
        retryRemaining: claimData.maxAttempts - newAttemptCount,
        claimStatus: "RETRYING"
      };
    }
  });

  return result;
}

/**
 * Server-side core implementation of recordVerificationFailure.
 * Records an eligible capture/verification problem (or GPS location violation) on the active claim.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ sessionId: string, courseCode: string, failureReason: string }} data
 */
async function recordVerificationFailureCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.sessionId || !data.courseCode || !data.failureReason) {
    throw new ServiceError("invalid-argument", "Missing required fields: sessionId, courseCode, and failureReason.");
  }

  const { sessionId, courseCode, failureReason } = data;
  const studentUid = authContext.uid;
  const claimId = `${sessionId}_${studentUid}`;

  const now = Date.now();

  return await db.runTransaction(async (transaction) => {
    const claimRef = db.collection("attendance_claims").doc(claimId);
    const claimDoc = await transaction.get(claimRef);

    if (!claimDoc.exists) {
      throw new ServiceError("not-found", "No active attendance claim found for this session.");
    }

    const claimData = claimDoc.data();
    if (claimData.status === "SUBMITTED" || claimData.status === "COMPLETED") {
      throw new ServiceError("already-exists", "Attendance has already been submitted for this session.");
    }

    const isGpsViolation = failureReason.toUpperCase().includes("GPS") || failureReason.toUpperCase().includes("LOCATION");

    const newStatus = isGpsViolation ? "REJECTED_GPS" : "VERIFICATION_FAILED";
    const retryEligible = !isGpsViolation && (claimData.attemptCount < claimData.maxAttempts);

    transaction.update(claimRef, {
      status: newStatus,
      failureReason,
      failedAt: now,
      retryEligible,
      updatedAt: now
    });

    return {
      success: true,
      claimId,
      status: newStatus,
      attemptCount: claimData.attemptCount,
      retryEligible,
      retryRemaining: retryEligible ? (claimData.maxAttempts - claimData.attemptCount) : 0
    };
  });
}

/**
 * Server-side core implementation of submitAttendanceRecord.
 * Authoritative backend enforcement of trust-score thresholds and status determination.
 *
 * Security & Policy Rules:
 * 1. Requires valid authentication (caller must be an authenticated student).
 * 2. Requires an existing active attendance claim issued by validateQrToken for the exact session & course.
 * 3. Enforces mandatory verification gates that CANNOT be bypassed by high trust score:
 *    - Liveness check must pass (livenessPassed === true).
 *    - Face verification must succeed (matchResult !== "NO_MATCH").
 *    - GPS geofence check must have passed (claim status cannot be REJECTED_GPS).
 * 4. Overall score must be an integer between 0 and 100.
 * 5. Applies exact threshold policy:
 *    - overallScore > 75: APPROVED (autoApproved = true)
 *    - overallScore < 40: REJECTED (autoApproved = false, below minimum threshold)
 *    - overallScore 40..75: REVIEW (autoApproved = false, Under Review — Awaiting Faculty Approval)
 * 6. Atomically writes the final attendance record using Admin SDK to attendance_records/${sessionId}_${studentUid}
 *    and consumes the attendance claim (marking it SUBMITTED).
 *
 * Client-side Computation Architectural Note:
 * Face feature extraction, anti-spoof liveness detection, and initial score arithmetic are computed
 * client-side on the student's mobile device to preserve student biometric privacy (raw facial images
 * and embeddings are never uploaded to third-party clouds without consent).
 * The server enforces authoritative policy boundaries:
 * - A submission is strictly rejected unless the student holds an unconsumed, server-issued attendance claim
 *   generated during the valid 25s window of the faculty's active dynamic QR rotation.
 * - Server-side idempotency prevents duplicate attendance submissions or re-use of expired claims.
 * - Score must conform to valid integer range [0, 100], and the status decision (APPROVED / REVIEW / REJECTED)
 *   is made exclusively by this server-side function via Firebase Admin SDK.
 * - Direct client-side creation of APPROVED records remains strictly blocked by firestore.rules.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string, displayName?: string } | null} authContext
 * @param {object} data
 */
async function submitAttendanceRecordCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.sessionId || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing required fields: sessionId and courseCode.");
  }

  const { sessionId, courseCode } = data;
  const studentUid = authContext.uid;
  const studentEmail = (authContext.email || (data.studentEmail || "")).toLowerCase();
  const recordId = `${sessionId}_${studentUid}`;

  // Validate score
  const overallScore = data.overallScore;
  if (typeof overallScore !== "number" || !Number.isInteger(overallScore) || overallScore < 0 || overallScore > 100) {
    throw new ServiceError("invalid-argument", "overallScore must be an integer between 0 and 100.");
  }

  const now = Date.now();

  return await db.runTransaction(async (transaction) => {
    // 1. Check if final record already exists (idempotency)
    const recordRef = db.collection("attendance_records").doc(recordId);
    const existingRecord = await transaction.get(recordRef);

    if (existingRecord.exists) {
      const recData = existingRecord.data();
      return {
        success: true,
        recordId,
        sessionId,
        courseCode,
        status: recData.status,
        autoApproved: recData.autoApproved === true,
        overallScore: recData.overallScore,
        alreadySubmitted: true
      };
    }

    // 2. Read and verify attendance claim
    const claimRef = db.collection("attendance_claims").doc(recordId);
    const claimDoc = await transaction.get(claimRef);

    if (!claimDoc.exists) {
      throw new ServiceError(
        "failed-precondition",
        "No valid attendance claim found for this session. A verified dynamic QR scan is required."
      );
    }

    const claimData = claimDoc.data();

    // Verify claim student UID
    if (claimData.studentUid !== studentUid) {
      throw new ServiceError("permission-denied", "Attendance claim belongs to a different student.");
    }

    // Verify course matches
    if (claimData.courseCode && claimData.courseCode !== courseCode) {
      throw new ServiceError("invalid-argument", "Attendance claim belongs to a different course.");
    }

    // Verify session matches
    if (claimData.sessionId !== sessionId) {
      throw new ServiceError("invalid-argument", "Attendance claim belongs to a different session.");
    }

    // Check claim status
    if (claimData.status === "SUBMITTED" || claimData.status === "COMPLETED") {
      throw new ServiceError("already-exists", "Attendance has already been recorded for this session.");
    }

    if (claimData.status === "REJECTED_GPS") {
      throw new ServiceError(
        "permission-denied",
        "Attendance rejected due to GPS location outside classroom. Retries or submissions are not permitted."
      );
    }

    // 3. Verify course is not archived
    const coursesSnapshot = await transaction.get(
      db.collection("courses").where("classCode", "==", courseCode).limit(1)
    );
    if (!coursesSnapshot.empty) {
      const courseDoc = coursesSnapshot.docs[0];
      if (courseDoc.data().isArchived === true) {
        throw new ServiceError("failed-precondition", "Cannot submit attendance for an archived course.");
      }
    }

    // 4. Mandatory Verification Gates
    // Mandatory gates cannot be bypassed by high trust score:
    // - Liveness check must have passed
    // - Face match cannot be NO_MATCH
    // - Caller must not report an active failure reason indicating location/liveness violation
    const livenessPassed = data.livenessPassed === true;
    const isFaceNoMatch = data.matchResult === "NO_MATCH";
    const explicitRejection = Boolean(data.rejectionReason);

    let status;
    let autoApproved = false;
    let finalRejectionReason = data.rejectionReason || null;

    if (!livenessPassed) {
      status = "REJECTED";
      autoApproved = false;
      finalRejectionReason = "Liveness verification check failed.";
    } else if (isFaceNoMatch) {
      status = "REJECTED";
      autoApproved = false;
      finalRejectionReason = "Face recognition failed: biometric signature did not match enrolled student reference.";
    } else if (explicitRejection && !finalRejectionReason.includes("UNCERTAIN_ACCURACY")) {
      status = "REJECTED";
      autoApproved = false;
    } else {
      // Apply exact trust-score thresholds
      // Score > 75: APPROVED
      // Score < 40: REJECTED
      // Score 40..75: REVIEW
      if (overallScore > 75) {
        status = "APPROVED";
        autoApproved = true;
        finalRejectionReason = null;
      } else if (overallScore < 40) {
        status = "REJECTED";
        autoApproved = false;
        finalRejectionReason = `Overall trust score insufficient (${overallScore}/100). Minimum passing threshold is 40.`;
      } else {
        status = "REVIEW";
        autoApproved = false;
        finalRejectionReason = null;
      }
    }

    // 5. Construct and write the final attendance record
    const finalRecord = {
      id: recordId,
      sessionId,
      studentUid,
      studentEmail: studentEmail || claimData.studentEmail || "",
      studentName: data.studentName || authContext.displayName || "Student",
      courseCode,
      faceMatchScore: typeof data.faceMatchScore === "number" ? data.faceMatchScore : 0,
      matchPercentage: typeof data.matchPercentage === "number" ? data.matchPercentage : 0,
      matchResult: data.matchResult || "UNKNOWN",
      livenessChallenge: data.livenessChallenge || "BLINK",
      livenessPassed,
      status,
      autoApproved,
      overallScore,
      timestamp: now,
      rejectionReason: finalRejectionReason,
      selfieBase64: data.selfieBase64 || "",
      classroomPhotoBase64: data.classroomPhotoBase64 || ""
    };

    transaction.set(recordRef, finalRecord);

    // 6. Update claim to SUBMITTED
    transaction.update(claimRef, {
      status: "SUBMITTED",
      finalRecordId: recordId,
      finalStatus: status,
      submittedAt: now,
      updatedAt: now
    });

    return {
      success: true,
      recordId,
      sessionId,
      courseCode,
      status,
      autoApproved,
      overallScore,
      alreadySubmitted: false
    };
  });
}

module.exports = {
  ServiceError,
  startAttendanceSessionCore,
  stopAttendanceSessionCore,
  generateQrTokenCore,
  validateQrTokenCore,
  recordVerificationFailureCore,
  submitAttendanceRecordCore
};
