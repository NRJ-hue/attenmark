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
  validateQrTokenCore
} = require("./qr_service");

/**
 * Cloud Function: startAttendanceSession
 * Callable by authenticated faculty to start an attendance session.
 */
exports.startAttendanceSession = functions.https.onCall(async (data, context) => {
  try {
    const authContext = context.auth
      ? { uid: context.auth.uid, email: context.auth.token.email || "" }
      : null;

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
exports.stopAttendanceSession = functions.https.onCall(async (data, context) => {
  try {
    const authContext = context.auth
      ? { uid: context.auth.uid, email: context.auth.token.email || "" }
      : null;

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
exports.generateQrToken = functions.https.onCall(async (data, context) => {
  try {
    const authContext = context.auth
      ? { uid: context.auth.uid, email: context.auth.token.email || "" }
      : null;

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
exports.validateQrToken = functions.https.onCall(async (data, context) => {
  try {
    const authContext = context.auth
      ? { uid: context.auth.uid, email: context.auth.token.email || "" }
      : null;

    return await validateQrTokenCore(db, authContext, data);
  } catch (err) {
    if (err instanceof ServiceError) {
      throw new functions.https.HttpsError(err.code, err.message);
    }
    console.error("Unexpected error in validateQrToken:", err);
    throw new functions.https.HttpsError("internal", err.message || "An unexpected error occurred.");
  }
});
