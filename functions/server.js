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

// Initialize Firebase Admin SDK
let appInstance;
if (!getApps().length) {
  let credential = null;

  if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      credential = cert(sa);
    } catch (e) {
      console.error("Warning: Failed to parse FIREBASE_SERVICE_ACCOUNT JSON:", e.message);
    }
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

const app = express();

// Enable JSON body parsing with reasonable size limit
app.use(express.json({ limit: "1mb" }));

// Configure CORS for authorized origins
const allowedOrigins = [
  "https://attendmark-c2f07.web.app",
  "https://attendmark-c2f07.firebaseapp.com",
  "http://localhost:5000",
  "http://127.0.0.1:5000",
  "http://localhost:3000",
  "http://127.0.0.1:3000"
];

app.use(
  cors({
    origin: (origin, callback) => {
      // Allow requests with no origin (e.g. mobile apps, curl, server-to-server)
      if (!origin) return callback(null, true);
      if (allowedOrigins.includes(origin) || origin.endsWith(".web.app") || origin.endsWith(".firebaseapp.com") || origin.endsWith(".onrender.com")) {
        return callback(null, true);
      }
      return callback(null, true); // Permissive for API consumers, authenticated via ID token
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

// Start listening if run directly
if (require.main === module) {
  const PORT = process.env.PORT || 3000;
  const HOST = "0.0.0.0";
  app.listen(PORT, HOST, () => {
    console.log(`AttendIQ Backend API listening on ${HOST}:${PORT} (project: ${PROJECT_ID})`);
  });
}

module.exports = app;
