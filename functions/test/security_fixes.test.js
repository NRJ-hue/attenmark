/**
 * Regression tests for the pre-deployment security fixes.
 *
 * Covers:
 *   A – CORS enforcement (allowed origins, rejected origins, preflight, no-origin)
 *   B – Geofence radius clamp (server enforces max; caller cannot inflate)
 *   C – Atomic profile bootstrap (concurrent creates return correct result)
 *   D – stopAttendanceSession rejects missing sessionId instead of silently
 *       leaving the historical record active
 */

const assert = require("assert");
const http = require("http");
const { getApps, initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const config = require("../config");
const { bootstrapProfileCore } = require("../profile_service");
const {
  startAttendanceSessionCore,
  stopAttendanceSessionCore
} = require("../qr_service");

// ─── Test-mode Firebase setup ────────────────────────────────────────────────
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
process.env.GCLOUD_PROJECT = "attendmark-c2f07";
process.env.NODE_ENV = "test";

if (!getApps().length) {
  initializeApp({ projectId: "attendmark-c2f07" });
}
const db = getFirestore();

// Load the Express app AFTER setting NODE_ENV=test so IS_PRODUCTION === false
// and the process.exit(1) startup guard is inactive.
const app = require("../server");

// ─────────────────────────────────────────────────────────────────────────────
// A. CORS Enforcement
// ─────────────────────────────────────────────────────────────────────────────
describe("Security Fix A — CORS Enforcement", function () {
  this.timeout(10000);

  let server;
  let baseUrl;

  before(function (done) {
    server = http.createServer(app);
    server.listen(0, "127.0.0.1", () => {
      baseUrl = `http://127.0.0.1:${server.address().port}`;
      done();
    });
  });

  after(function (done) {
    if (server) server.close(done);
    else done();
  });

  // Helper: issue a real HTTP request and return { status, headers, body }
  async function makeRequest(method, path, origin, extraHeaders = {}) {
    const headers = { "Content-Type": "application/json" };
    if (origin !== undefined) headers["Origin"] = origin;
    Object.assign(headers, extraHeaders);

    const res = await fetch(`${baseUrl}${path}`, {
      method,
      headers,
      body: method === "POST" ? JSON.stringify({}) : undefined
    });
    const text = await res.text().catch(() => "");
    return { status: res.status, headers: res.headers, body: text };
  }

  it("allows a request from the production Firebase Hosting origin", async function () {
    const { headers } = await makeRequest("GET", "/health", "https://attendmark-c2f07.web.app");
    const acao = headers.get("access-control-allow-origin");
    assert.strictEqual(acao, "https://attendmark-c2f07.web.app",
      "Production origin must be reflected in Access-Control-Allow-Origin");
  });

  it("allows a request from the alternate Firebase Hosting domain (firebaseapp.com)", async function () {
    const { headers } = await makeRequest("GET", "/health", "https://attendmark-c2f07.firebaseapp.com");
    const acao = headers.get("access-control-allow-origin");
    assert.strictEqual(acao, "https://attendmark-c2f07.firebaseapp.com");
  });

  it("allows a local development origin (http://127.0.0.1:5000) in test/dev mode", async function () {
    // NODE_ENV === "test" => IS_PRODUCTION === false => dev origins are allowed
    const { headers } = await makeRequest("GET", "/health", "http://127.0.0.1:5000");
    const acao = headers.get("access-control-allow-origin");
    assert.strictEqual(acao, "http://127.0.0.1:5000",
      "Local dev origin must be allowed when NODE_ENV is test");
  });

  it("allows a request with NO Origin header (native Android / curl / server-to-server)", async function () {
    // No Origin header — fetch() with no Origin means origin is undefined.
    const res = await fetch(`${baseUrl}/health`, { method: "GET" });
    assert.strictEqual(res.status, 200,
      "Requests without Origin must reach the handler — auth is still enforced separately");
  });

  it("rejects an unrelated Firebase Hosting domain (*.web.app) with no wildcard allowance", async function () {
    const { status } = await makeRequest(
      "OPTIONS", "/api/bootstrap-profile",
      "https://attacker-project.web.app",
      { "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "Authorization" }
    );
    assert.ok(status >= 400, "Unrelated *.web.app origin must be rejected");
  });

  it("rejects an unrelated Render service (*.onrender.com) with no wildcard allowance", async function () {
    const { status } = await makeRequest(
      "OPTIONS", "/api/bootstrap-profile",
      "https://attacker-app.onrender.com",
      { "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "Authorization" }
    );
    assert.ok(status >= 400, "Unrelated *.onrender.com origin must be rejected");
  });

  it("rejects a browser request from an unknown third-party origin in test mode", async function () {
    const { status } = await makeRequest(
      "OPTIONS", "/api/bootstrap-profile",
      "https://evil.example.com",
      { "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "Authorization" }
    );
    assert.ok(status >= 400, `Expected a non-2xx status for rejected origin, got ${status}`);
  });

  it("handles preflight OPTIONS correctly for the production origin", async function () {
    const { status, headers } = await makeRequest(
      "OPTIONS", "/api/bootstrap-profile",
      "https://attendmark-c2f07.web.app",
      {
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "Authorization,Content-Type"
      }
    );
    assert.strictEqual(status, 204,
      "Preflight from a valid origin must return 204");
    const acao = headers.get("access-control-allow-origin");
    assert.strictEqual(acao, "https://attendmark-c2f07.web.app");
    const acam = headers.get("access-control-allow-methods") || "";
    assert.ok(acam.includes("POST"), "Preflight must advertise POST");
    const acah = headers.get("access-control-allow-headers") || "";
    assert.ok(acah.toLowerCase().includes("authorization"),
      "Preflight must advertise Authorization header");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// B. Geofence Radius Clamp
// ─────────────────────────────────────────────────────────────────────────────
describe("Security Fix B — Server-Controlled Geofence Radius Policy", function () {
  this.timeout(12000);

  const FACULTY_UID = "sec-fac-geo-" + Date.now();
  const FACULTY_EMAIL = "geofac@test.com";
  const COURSE_CODE = "GEOCOURSE-" + Date.now();

  const validCoords = { latitude: 28.6139, longitude: 77.2090 };

  before(async function () {
    // Seed faculty profile and course
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty",
      email: FACULTY_EMAIL,
      name: "Geo Faculty"
    });
    await db.collection("courses").add({
      classCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      courseName: "Geo Test Course"
    });
  });

  it("uses the server-configured demo radius (100m) when no caller radius is supplied in demo mode", async function () {
    const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
    const result = await startAttendanceSessionCore(db, auth, {
      courseCode: COURSE_CODE,
      ...validCoords
    });
    assert.strictEqual(result.geofenceRadiusMeters, 100,
      "Expected 100m (demo default) when caller supplies nothing");
  });

  it("local demo mode: client requesting 999999m cannot enlarge the 100m radius", async function () {
    const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
    const result = await startAttendanceSessionCore(db, auth, {
      courseCode: COURSE_CODE,
      ...validCoords,
      geofenceRadiusMeters: 999999
    });
    assert.strictEqual(result.geofenceRadiusMeters, 100,
      "Client requesting 999999m must receive server-configured 100m in demo mode");
  });

  it("local demo mode: client requesting 1m cannot reduce the 100m radius", async function () {
    const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
    const result = await startAttendanceSessionCore(db, auth, {
      courseCode: COURSE_CODE,
      ...validCoords,
      geofenceRadiusMeters: 1
    });
    assert.strictEqual(result.geofenceRadiusMeters, 100,
      "Client requesting 1m must receive server-configured 100m in demo mode");
  });

  it("local demo mode: invalid, zero, negative, and missing values result in 100m", async function () {
    const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
    for (const val of [0, -50, "invalid", null, undefined]) {
      const result = await startAttendanceSessionCore(db, auth, {
        courseCode: COURSE_CODE,
        ...validCoords,
        geofenceRadiusMeters: val
      });
      assert.strictEqual(result.geofenceRadiusMeters, 100,
        `Expected 100m when client provides geofenceRadiusMeters=${val}`);
    }
  });

  it("production mode: default radius is strictly 50m", async function () {
    const origNodeEnv = process.env.NODE_ENV;
    const origFirestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
    const origFuncEmulator = process.env.FUNCTIONS_EMULATOR;
    const origConfigDemo = config.isDemoMode;

    try {
      process.env.NODE_ENV = "production";
      delete process.env.FIRESTORE_EMULATOR_HOST;
      delete process.env.FUNCTIONS_EMULATOR;
      config.isDemoMode = false;

      const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const result = await startAttendanceSessionCore(db, auth, {
        courseCode: COURSE_CODE,
        ...validCoords
      });

      assert.strictEqual(result.geofenceRadiusMeters, 50,
        "Production environment default radius must strictly be 50m");
      assert.strictEqual(result.location.geofenceRadiusMeters, 50);

      const sessionDoc = await db.collection("sessions").doc(COURSE_CODE).get();
      assert.strictEqual(sessionDoc.data().geofenceRadiusMeters, 50);
      assert.strictEqual(sessionDoc.data().location.geofenceRadiusMeters, 50);
    } finally {
      process.env.NODE_ENV = origNodeEnv;
      if (origFirestoreHost) process.env.FIRESTORE_EMULATOR_HOST = origFirestoreHost;
      if (origFuncEmulator) process.env.FUNCTIONS_EMULATOR = origFuncEmulator;
      config.isDemoMode = origConfigDemo;
    }
  });

  it("production mode: client requesting 999999m cannot enlarge the 50m radius", async function () {
    const origNodeEnv = process.env.NODE_ENV;
    const origFirestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
    const origFuncEmulator = process.env.FUNCTIONS_EMULATOR;
    const origConfigDemo = config.isDemoMode;

    try {
      process.env.NODE_ENV = "production";
      delete process.env.FIRESTORE_EMULATOR_HOST;
      delete process.env.FUNCTIONS_EMULATOR;
      config.isDemoMode = false;

      const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const result = await startAttendanceSessionCore(db, auth, {
        courseCode: COURSE_CODE,
        ...validCoords,
        geofenceRadiusMeters: 999999
      });

      assert.strictEqual(result.geofenceRadiusMeters, 50,
        "Production environment must strictly enforce 50m, ignoring client request of 999999m");
    } finally {
      process.env.NODE_ENV = origNodeEnv;
      if (origFirestoreHost) process.env.FIRESTORE_EMULATOR_HOST = origFirestoreHost;
      if (origFuncEmulator) process.env.FUNCTIONS_EMULATOR = origFuncEmulator;
      config.isDemoMode = origConfigDemo;
    }
  });

  it("production mode: client requesting 1m cannot reduce the 50m radius", async function () {
    const origNodeEnv = process.env.NODE_ENV;
    const origFirestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
    const origFuncEmulator = process.env.FUNCTIONS_EMULATOR;
    const origConfigDemo = config.isDemoMode;

    try {
      process.env.NODE_ENV = "production";
      delete process.env.FIRESTORE_EMULATOR_HOST;
      delete process.env.FUNCTIONS_EMULATOR;
      config.isDemoMode = false;

      const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      const result = await startAttendanceSessionCore(db, auth, {
        courseCode: COURSE_CODE,
        ...validCoords,
        geofenceRadiusMeters: 1
      });

      assert.strictEqual(result.geofenceRadiusMeters, 50,
        "Production environment must strictly enforce 50m, ignoring client request of 1m");
    } finally {
      process.env.NODE_ENV = origNodeEnv;
      if (origFirestoreHost) process.env.FIRESTORE_EMULATOR_HOST = origFirestoreHost;
      if (origFuncEmulator) process.env.FUNCTIONS_EMULATOR = origFuncEmulator;
      config.isDemoMode = origConfigDemo;
    }
  });

  it("production mode: invalid, zero, negative, and missing values result in 50m", async function () {
    const origNodeEnv = process.env.NODE_ENV;
    const origFirestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
    const origFuncEmulator = process.env.FUNCTIONS_EMULATOR;
    const origConfigDemo = config.isDemoMode;

    try {
      process.env.NODE_ENV = "production";
      delete process.env.FIRESTORE_EMULATOR_HOST;
      delete process.env.FUNCTIONS_EMULATOR;
      config.isDemoMode = false;

      const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
      for (const val of [0, -100, "foo", null, undefined]) {
        const result = await startAttendanceSessionCore(db, auth, {
          courseCode: COURSE_CODE,
          ...validCoords,
          geofenceRadiusMeters: val
        });
        assert.strictEqual(result.geofenceRadiusMeters, 50,
          `Expected 50m in production when client provides geofenceRadiusMeters=${val}`);
      }
    } finally {
      process.env.NODE_ENV = origNodeEnv;
      if (origFirestoreHost) process.env.FIRESTORE_EMULATOR_HOST = origFirestoreHost;
      if (origFuncEmulator) process.env.FUNCTIONS_EMULATOR = origFuncEmulator;
      config.isDemoMode = origConfigDemo;
    }
  });

  it("config.DEMO_GEOFENCE_RADIUS_METERS is 100 in test/dev mode", function () {
    assert.strictEqual(config.DEMO_GEOFENCE_RADIUS_METERS, 100,
      "Demo geofence radius must be exactly 100m");
  });

  it("config.PRODUCTION_GEOFENCE_RADIUS_METERS is 50", function () {
    assert.strictEqual(config.PRODUCTION_GEOFENCE_RADIUS_METERS, 50,
      "Production geofence radius must be exactly 50m");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// C. Atomic Profile Bootstrap
// ─────────────────────────────────────────────────────────────────────────────
describe("Security Fix C — Atomic Profile Bootstrap", function () {
  this.timeout(12000);

  it("handles a simulated concurrent-create race: second create returns existing profile unchanged", async function () {
    const uid = "race-uid-" + Date.now();
    const email = "22race001@college.edu";
    const auth = { uid, email };

    // Simulate the race: manually write the document AFTER the caller's get()
    // but BEFORE the create() by seeding it now. Our create() will then see
    // ALREADY_EXISTS and should return the pre-existing document.
    await db.collection("users").doc(uid).set({
      uid,
      email,
      name: "Pre-existing Name",
      role: "student",
      createdAt: 1000,
      customField: "should-be-preserved"
    });

    const result = await bootstrapProfileCore(db, auth, { name: "Race Loser Name" });

    assert.strictEqual(result.uid, uid);
    assert.strictEqual(result.email, email);
    assert.strictEqual(result.role, "student",
      "Role from existing document must be preserved");
    assert.strictEqual(result.name, "Pre-existing Name",
      "Name from existing document must be preserved, not overwritten by the losing caller");
    assert.strictEqual(result.isNew, false,
      "isNew must be false when an existing document was found");

    // Verify the stored document is unchanged
    const stored = await db.collection("users").doc(uid).get();
    assert.strictEqual(stored.data().customField, "should-be-preserved",
      "Extra fields on the existing document must not be lost");
    assert.strictEqual(stored.data().createdAt, 1000,
      "createdAt on the existing document must not be overwritten");
  });

  it("normal first-time profile creation still works correctly", async function () {
    const uid = "first-time-" + Date.now();
    const email = "22cs100@college.edu";
    const auth = { uid, email };

    const result = await bootstrapProfileCore(db, auth, { name: "New Student" });

    assert.strictEqual(result.uid, uid);
    assert.strictEqual(result.email, email);
    assert.strictEqual(result.role, "student");
    assert.strictEqual(result.name, "New Student");
    assert.strictEqual(result.isNew, true);

    const stored = await db.collection("users").doc(uid).get();
    assert.ok(stored.exists);
    assert.strictEqual(stored.data().role, "student");
  });

  it("role classification rule is unchanged after atomicity fix (2-digit prefix = student)", function () {
    const { classifyRoleFromEmail } = require("../profile_service");
    assert.strictEqual(classifyRoleFromEmail("22cs100@college.edu"), "student");
    assert.strictEqual(classifyRoleFromEmail("faculty@test.com"), "faculty");
    assert.strictEqual(classifyRoleFromEmail("01xyz@test.com"), "student");
    assert.strictEqual(classifyRoleFromEmail("1abc@test.com"), "faculty");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// D. stopAttendanceSession — missing sessionId guard
// ─────────────────────────────────────────────────────────────────────────────
describe("Security Fix D — stopAttendanceSession rejects missing sessionId", function () {
  this.timeout(12000);

  const FACULTY_UID = "stop-fac-" + Date.now();
  const FACULTY_EMAIL = "stopfac@test.com";
  const COURSE_CODE = "STOPCOURSE-" + Date.now();

  before(async function () {
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty",
      email: FACULTY_EMAIL,
      name: "Stop Faculty"
    });
  });

  it("throws failed-precondition when the active session pointer has no sessionId", async function () {
    // Seed a sessions/{courseCode} document WITHOUT a sessionId field.
    // This simulates the data-corruption edge case described in the audit.
    await db.collection("sessions").doc(COURSE_CODE).set({
      courseCode: COURSE_CODE,
      facultyEmail: FACULTY_EMAIL,
      isActive: true,
      startedAt: Date.now(),
      // sessionId is intentionally absent
    });

    const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };

    await assert.rejects(
      async () => stopAttendanceSessionCore(db, auth, { courseCode: COURSE_CODE }),
      (err) => {
        assert.strictEqual(err.code, "failed-precondition",
          "Must throw failed-precondition, not silently return success");
        assert.ok(
          err.message.includes("sessionId"),
          `Error message must mention sessionId; got: "${err.message}"`
        );
        return true;
      }
    );

    // The active session pointer must still be active (we did not silently mutate it)
    const sessionDoc = await db.collection("sessions").doc(COURSE_CODE).get();
    assert.strictEqual(sessionDoc.data().isActive, true,
      "The active session pointer must not have been modified by the failed stop attempt");
  });

  it("normal stop still succeeds when sessionId is present", async function () {
    const sessionId = "valid-session-" + Date.now();
    const courseCode = "STOPCOURSE-OK-" + Date.now();

    // Seed faculty profile (may already exist from before())
    await db.collection("users").doc(FACULTY_UID).set({
      role: "faculty", email: FACULTY_EMAIL, name: "Stop Faculty"
    });

    // Seed a well-formed active session
    await db.collection("sessions").doc(courseCode).set({
      courseCode,
      sessionId,
      facultyEmail: FACULTY_EMAIL,
      isActive: true,
      startedAt: Date.now()
    });
    await db.collection("attendance_sessions").doc(sessionId).set({
      courseCode,
      sessionId,
      facultyEmail: FACULTY_EMAIL,
      isActive: true,
      startedAt: Date.now()
    });

    const auth = { uid: FACULTY_UID, email: FACULTY_EMAIL };
    const result = await stopAttendanceSessionCore(db, auth, { courseCode });

    assert.strictEqual(result.success, true);
    assert.strictEqual(result.sessionId, sessionId);

    // Both documents must now be inactive
    const activePtr = await db.collection("sessions").doc(courseCode).get();
    assert.strictEqual(activePtr.data().isActive, false,
      "Active session pointer must be marked inactive");

    const historical = await db.collection("attendance_sessions").doc(sessionId).get();
    assert.strictEqual(historical.data().isActive, false,
      "Historical attendance_sessions record must be marked inactive");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E. Firebase Admin Credentials Fail-Fast Startup in Production
// ─────────────────────────────────────────────────────────────────────────────
describe("Security Fix E — Firebase Admin Credentials Startup Fail-Fast", function () {
  this.timeout(10000);
  const { spawnSync } = require("child_process");
  const path = require("path");
  const serverPath = path.resolve(__dirname, "../server.js");

  it("fails fast with exit code 1 when FIREBASE_SERVICE_ACCOUNT is missing in production", function () {
    const res = spawnSync("node", [serverPath], {
      env: {
        ...process.env,
        NODE_ENV: "production",
        FIREBASE_PROJECT_ID: "attendmark-c2f07",
        FIREBASE_SERVICE_ACCOUNT: ""
      },
      encoding: "utf8"
    });

    assert.strictEqual(res.status, 1, "Must exit with status code 1");
    const stderr = res.stderr || "";
    assert.ok(
      stderr.includes("FIREBASE_SERVICE_ACCOUNT environment variable is not set"),
      `stderr should explain that FIREBASE_SERVICE_ACCOUNT is not set. Got: ${stderr}`
    );
  });

  it("fails fast with exit code 1 when FIREBASE_SERVICE_ACCOUNT is malformed JSON in production", function () {
    const res = spawnSync("node", [serverPath], {
      env: {
        ...process.env,
        NODE_ENV: "production",
        FIREBASE_PROJECT_ID: "attendmark-c2f07",
        FIREBASE_SERVICE_ACCOUNT: "{ bad json :::"
      },
      encoding: "utf8"
    });

    assert.strictEqual(res.status, 1, "Must exit with status code 1");
    const stderr = res.stderr || "";
    assert.ok(
      stderr.includes("JSON parse failed") || stderr.includes("Failed to parse FIREBASE_SERVICE_ACCOUNT"),
      `stderr should explain JSON parse error. Got: ${stderr}`
    );
  });

  it("fails fast with exit code 1 when FIREBASE_SERVICE_ACCOUNT project_id does not match", function () {
    const mismatchedSa = JSON.stringify({
      project_id: "other-wrong-project",
      client_email: "test@other-wrong-project.iam.gserviceaccount.com",
      private_key: "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7\n-----END PRIVATE KEY-----\n"
    });

    const res = spawnSync("node", [serverPath], {
      env: {
        ...process.env,
        NODE_ENV: "production",
        FIREBASE_PROJECT_ID: "attendmark-c2f07",
        FIREBASE_SERVICE_ACCOUNT: mismatchedSa
      },
      encoding: "utf8"
    });

    assert.strictEqual(res.status, 1, "Must exit with status code 1");
    const stderr = res.stderr || "";
    assert.ok(
      stderr.includes("does not match expected Firebase project"),
      `stderr should explain project_id mismatch. Got: ${stderr}`
    );
  });
});
