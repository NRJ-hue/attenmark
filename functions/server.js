/**
 * AttendIQ Express API Server for Render Cloud Deployment
 *
 * Provides authenticated HTTPS API endpoints for:
 * - Profile bootstrap & exact role determination
 * - Session lifecycle management (start/stop)
 * - QR token issuance (with 30s expiry & 25s rotation)
 * - QR validation & attendance claim issuance
 * - Capture & GPS verification failure recording
 */
const express = require("express");
const cors = require("cors");
const { getApps, initializeApp, cert } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");

const PROJECT_ID = process.env.FIREBASE_PROJECT_ID || process.env.GCLOUD_PROJECT || "attendmark-c2f07";

// ─── Firebase Admin SDK Initialization ──────────────────────────────────────
// In production (NODE_ENV === "production"), a valid FIREBASE_SERVICE_ACCOUNT
// JSON env var is REQUIRED. Render free-tier has no implicit GCP credentials,
// so failing to set the secret means verifyIdToken() will throw on every call.
// We fail fast here with a clear, non-sensitive message rather than silently
// failing on every request after startup.

const IS_PRODUCTION = process.env.NODE_ENV === "production";
const IS_TEST = process.env.NODE_ENV === "test";

let appInstance;
if (!getApps().length) {
  let credential = null;
  let credentialParseError = null;

  if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);

      // Sanity-check: the service account project_id should match our expected project.
      // This catches copy-paste mistakes (e.g. using a staging SA for production).
      if (sa.project_id && sa.project_id !== PROJECT_ID) {
        const msg =
          `FATAL: FIREBASE_SERVICE_ACCOUNT project_id "${sa.project_id}" does not match ` +
          `expected Firebase project "${PROJECT_ID}". Check Render environment variables.`;
        console.error(msg);
        credentialParseError = `project_id "${sa.project_id}" does not match expected project "${PROJECT_ID}"`;
        if (IS_PRODUCTION) process.exit(1);
      } else {
        credential = cert(sa);
      }
    } catch (e) {
      credentialParseError = e.message;
      console.error("ERROR: Failed to parse FIREBASE_SERVICE_ACCOUNT JSON:", e.message);
    }
  }

  // In production, a credential is mandatory. Without it, verifyIdToken() will
  // fail on Render (no implicit Application Default Credential is available).
  if (IS_PRODUCTION && !credential) {
    const reason = credentialParseError
      ? `JSON parse failed: ${credentialParseError}`
      : "FIREBASE_SERVICE_ACCOUNT environment variable is not set.";
    console.error(
      `FATAL: Cannot start AttendIQ backend in production without Firebase credentials. ${reason}\n` +
      "Set FIREBASE_SERVICE_ACCOUNT in the Render dashboard with the full service-account JSON."
    );
    process.exit(1);
  }

  const appOptions = { projectId: PROJECT_ID };
  if (credential) {
    appOptions.credential = credential;
  }
  appInstance = initializeApp(appOptions);
} else {
  appInstance = getApps()[0];
}

const db = getFirestore(appInstance);
const auth = getAuth(appInstance);

const {
  ServiceError,
  startAttendanceSessionCore,
  stopAttendanceSessionCore,
  generateQrTokenCore,
  validateQrTokenCore,
  recordVerificationFailureCore
} = require("./qr_service");

const {
  classifyRoleFromEmail,
  bootstrapProfileCore
} = require("./profile_service");

const {
  leaveCourseCore,
  archiveCourseCore,
  unarchiveCourseCore
} = require("./course_service");

const app = express();

// Enable JSON body parsing with reasonable size limit
app.use(express.json({ limit: "1mb" }));

// ─── CORS Configuration ──────────────────────────────────────────────────────
// Origins explicitly allowed in production (Firebase Hosting domains).
// In development/test, local origins are also permitted.
// Note: CORS is NOT a substitute for authentication. Every protected endpoint
// still verifies the Firebase ID token regardless of origin.
//
// Requests with no Origin header (native Android, curl, server-to-server)
// are allowed unconditionally — they pass through to token verification.
const PRODUCTION_ORIGINS = [
  "https://attendmark-c2f07.web.app",
  "https://attendmark-c2f07.firebaseapp.com"
];

const DEV_ORIGINS = [
  "http://localhost:5000",
  "http://127.0.0.1:5000",
  "http://localhost:3000",
  "http://127.0.0.1:3000"
];

// Origins allowed at runtime — always includes production; dev origins are
// added only when not running in production.
const ALLOWED_ORIGINS = IS_PRODUCTION
  ? PRODUCTION_ORIGINS
  : [...PRODUCTION_ORIGINS, ...DEV_ORIGINS];

app.use(
  cors({
    origin: (origin, callback) => {
      // No origin = native app / curl / server-to-server — pass through.
      // Authentication and authorization are strictly enforced downstream
      // by the authenticateToken middleware on every protected endpoint.
      if (!origin) return callback(null, true);

      // Enforce exact origin match against configured allowlist.
      // Avoid broad wildcard suffix or regex rules that could allow unrelated
      // web apps, Render services, or preview deployments.
      const isAllowed = ALLOWED_ORIGINS.includes(origin);

      if (isAllowed) return callback(null, true);

      // Reject unexpected browser origins. The request will receive a 403 from
      // the browser's CORS preflight — no response body is needed.
      return callback(new Error(`CORS: origin "${origin}" is not permitted.`));
    },
    credentials: true,
    methods: ["GET", "POST", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization", "X-Requested-With", "Accept"]
  })
);

// ─── Health Check Endpoint ───────────────────────────────────────────────────
app.get(["/health", "/api/health"], (req, res) => {
  res.status(200).json({
    status: "healthy",
    service: "AttendIQ Backend API",
    projectId: PROJECT_ID,
    timestamp: new Date().toISOString()
  });
});

// ─── Authentication Middleware ───────────────────────────────────────────────
async function authenticateToken(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return res.status(401).json({
      error: {
        code: "unauthenticated",
        message: "Missing or malformed Authorization header. Expected 'Bearer <Firebase_ID_Token>'."
      }
    });
  }

  const idToken = authHeader.split("Bearer ")[1].trim();

  // Test suite mock token support for local testing without network round-trips
  if (process.env.NODE_ENV === "test" && req.headers["x-test-auth-uid"]) {
    req.authContext = {
      uid: req.headers["x-test-auth-uid"],
      email: (req.headers["x-test-auth-email"] || "").toLowerCase()
    };
    return next();
  }

  try {
    const decodedToken = await auth.verifyIdToken(idToken);
    req.authContext = {
      uid: decodedToken.uid,
      email: (decodedToken.email || "").toLowerCase()
    };
    next();
  } catch (err) {
    return res.status(401).json({
      error: {
        code: "unauthenticated",
        message: "Firebase ID token verification failed: " + (err.message || "Invalid token.")
      }
    });
  }
}

// ─── Helper for Error Mapping ────────────────────────────────────────────────
function handleError(res, err) {
  if (err instanceof ServiceError) {
    const statusMap = {
      unauthenticated: 401,
      "permission-denied": 403,
      "not-found": 404,
      "invalid-argument": 400,
      "already-exists": 409,
      "deadline-exceeded": 408,
      "failed-precondition": 412,
      "resource-exhausted": 429
    };
    const status = statusMap[err.code] || 400;
    return res.status(status).json({
      error: {
        code: err.code,
        message: err.message
      }
    });
  }

  console.error("Unhandled API error:", err);
  return res.status(500).json({
    error: {
      code: "internal",
      message: err.message || "An unexpected internal server error occurred."
    }
  });
}

function extractPayload(req) {
  return req.body && req.body.data !== undefined && req.body.data !== null
    ? req.body.data
    : req.body || {};
}

function sendResponse(res, result) {
  // Return response compatible with both standard REST clients and Firebase Callable SDKs
  return res.status(200).json({
    ...result,
    data: result
  });
}

// ─── API Routes ──────────────────────────────────────────────────────────────

/**
 * POST /api/bootstrap-profile
 * Creates or retrieves user profile with authoritative server-side role assignment.
 */
app.post(["/api/bootstrap-profile", "/bootstrapProfile"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await bootstrapProfileCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/startAttendanceSession
 * Starts an attendance session with configured classroom coordinates and geofence.
 */
app.post(["/api/startAttendanceSession", "/startAttendanceSession"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await startAttendanceSessionCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/stopAttendanceSession
 * Atomically marks an attendance session and its active pointer inactive.
 */
app.post(["/api/stopAttendanceSession", "/stopAttendanceSession"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await stopAttendanceSessionCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/generateQrToken
 * Generates an opaque, time-bound QR token for an active session.
 */
app.post(["/api/generateQrToken", "/generateQrToken"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await generateQrTokenCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/validateQrToken
 * Validates a scanned QR token, enforces course enrollment, and creates attendance claim.
 */
app.post(["/api/validateQrToken", "/validateQrToken"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await validateQrTokenCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/recordVerificationFailure
 * Records an eligible capture or GPS verification failure on a student's claim.
 */
app.post(["/api/recordVerificationFailure", "/recordVerificationFailure"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await recordVerificationFailureCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/leaveCourse
 * Student leaves a course; securely removes strictly the calling student's email.
 */
app.post(["/api/leaveCourse", "/leaveCourse"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await leaveCourseCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/archiveCourse
 * Faculty archives an owned course; automatically stops any active session.
 */
app.post(["/api/archiveCourse", "/archiveCourse"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await archiveCourseCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

/**
 * POST /api/unarchiveCourse
 * Faculty unarchives an owned course.
 */
app.post(["/api/unarchiveCourse", "/unarchiveCourse"], authenticateToken, async (req, res) => {
  try {
    const data = extractPayload(req);
    const result = await unarchiveCourseCore(db, req.authContext, data);
    return sendResponse(res, result);
  } catch (err) {
    return handleError(res, err);
  }
});

// Start listening if run directly
if (require.main === module) {
  const PORT = process.env.PORT || 3000;
  const HOST = "0.0.0.0";
  app.listen(PORT, HOST, () => {
    console.log(`AttendIQ Backend API listening on ${HOST}:${PORT} (project: ${PROJECT_ID})`);
  });
}

module.exports = app;
