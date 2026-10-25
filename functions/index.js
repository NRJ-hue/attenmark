const functions = require("firebase-functions");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

if (!getApps().length) {
  initializeApp();
}

const db = getFirestore();
const {
  ServiceError,
  startAttendanceSessionCore,
  stopAttendanceSessionCore,
  generateQrTokenCore,
  validateQrTokenCore,
  recordVerificationFailureCore
} = require("./qr_service");

/**
 * Normalizes parameters for Callable functions across Firebase Functions 1st gen and 2nd gen.
 * In 2nd gen (firebase-functions v4+ / v7): onCall receives a single CallableRequest argument (req)
 * with req.data and req.auth.
 * In 1st gen or legacy callers: onCall receives (data, context) where context.auth exists.
 */
function extractCallableParams(arg1, arg2) {
  // Detect Firebase Functions v7 2nd gen CallableRequest:
  // In v7 runtime, handler receives (request, response) where response is a CallableResponse proxy (with sendChunk/signal).
  // In v7 unit tests, fn.run(request) receives (request, undefined) or (request, response).
  // In 1st gen, handler receives (data, context) where context has context.auth.
  const isSecondGen =
    arg1 &&
    typeof arg1 === "object" &&
    ("data" in arg1 || "auth" in arg1) &&
    (!arg2 || arg2.auth === undefined);

  if (isSecondGen) {
    const data = ("data" in arg1 && arg1.data !== undefined && arg1.data !== null) ? arg1.data : {};
    const auth = arg1.auth;
    const authContext = auth && auth.uid
      ? { uid: auth.uid, email: (auth.token && auth.token.email) || auth.email || "" }
      : null;
    return { data, authContext };
  }

  const data = arg1 || {};
  const context = arg2 || {};
  const auth = context.auth;
  const authContext = auth && auth.uid
    ? { uid: auth.uid, email: (auth.token && auth.token.email) || auth.email || "" }
    : null;
  return { data, authContext };
}

exports.extractCallableParams = extractCallableParams;

/**
 * Cloud Function: startAttendanceSession
 * Callable by authenticated faculty to start an attendance session.
 */
exports.startAttendanceSession = functions.https.onCall(async (request, response) => {
  try {
    const { data, authContext } = extractCallableParams(request, response);
    return await startAttendanceSessionCore(db, authContext, data);
  } catch (err) {
    if (err instanceof ServiceError) {
      throw new functions.https.HttpsError(err.code, err.message);
    }
    console.error("Unexpected error in startAttendanceSession:", err);
    throw new functions.https.HttpsError("internal", err.message || "An unexpected error occurred.");
  }
});

/**
 * Cloud Function: stopAttendanceSession
 * Callable by authenticated faculty to end an attendance session.
 */
exports.stopAttendanceSession = functions.https.onCall(async (request, response) => {
  try {
    const { data, authContext } = extractCallableParams(request, response);
    return await stopAttendanceSessionCore(db, authContext, data);
  } catch (err) {
    if (err instanceof ServiceError) {
      throw new functions.https.HttpsError(err.code, err.message);
    }
    console.error("Unexpected error in stopAttendanceSession:", err);
    throw new functions.https.HttpsError("internal", err.message || "An unexpected error occurred.");
  }
});

/**
 * Cloud Function: generateQrToken
 * Callable by authenticated faculty to issue a time-limited, session-bound QR token.
 */
exports.generateQrToken = functions.https.onCall(async (request, response) => {
  try {
    const { data, authContext } = extractCallableParams(request, response);
    return await generateQrTokenCore(db, authContext, data);
  } catch (err) {
    if (err instanceof ServiceError) {
      throw new functions.https.HttpsError(err.code, err.message);
    }
    console.error("Unexpected error in generateQrToken:", err);
    throw new functions.https.HttpsError("internal", err.message || "An unexpected error occurred.");
  }
});

/**
 * Cloud Function: validateQrToken
 * Callable by authenticated student to validate QR token, verify enrollment,
 * and atomically record an attendance claim.
 */
exports.validateQrToken = functions.https.onCall(async (request, response) => {
  try {
    const { data, authContext } = extractCallableParams(request, response);
    return await validateQrTokenCore(db, authContext, data);
  } catch (err) {
    if (err instanceof ServiceError) {
      throw new functions.https.HttpsError(err.code, err.message);
    }
    console.error("Unexpected error in validateQrToken:", err);
    throw new functions.https.HttpsError("internal", err.message || "An unexpected error occurred.");
  }
});

/**
 * Cloud Function: recordVerificationFailure
 * Callable by authenticated student to record an eligible capture failure or GPS violation on their claim.
 */
exports.recordVerificationFailure = functions.https.onCall(async (request, response) => {
  try {
    const { data, authContext } = extractCallableParams(request, response);
    return await recordVerificationFailureCore(db, authContext, data);
  } catch (err) {
    if (err instanceof ServiceError) {
      throw new functions.https.HttpsError(err.code, err.message);
    }
    console.error("Unexpected error in recordVerificationFailure:", err);
    throw new functions.https.HttpsError("internal", err.message || "An unexpected error occurred.");
  }
});
