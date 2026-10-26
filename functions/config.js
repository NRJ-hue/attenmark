/**
 * AttendIQ Cloud Functions Configuration
 *
 * Central configuration constants for QR token validity, refresh rates,
 * retry thresholds, and environment-specific geofence radii.
 */
const isDemoMode = Boolean(
  process.env.FUNCTIONS_EMULATOR === "true" ||
  process.env.FIRESTORE_EMULATOR_HOST ||
  process.env.NODE_ENV === "development" ||
  process.env.NODE_ENV === "test"
);

/**
 * Parses and validates an authoritative server-side geofence radius override
 * from environment variables.
 *
 * Supported settings:
 * - GEOFENCE_RADIUS_METERS: Positive integer in [10, 1000] (e.g. 100 for hosted college demo)
 * - DEMO_MODE=true / COLLEGE_DEMO=true: Convenience flag setting radius to 100m
 *
 * Returns the validated integer radius in meters, or null if no valid override is configured.
 */
function parseServerGeofenceOverride() {
  const raw = process.env.GEOFENCE_RADIUS_METERS;
  if (raw !== undefined && raw !== null && String(raw).trim() !== "") {
    const parsed = Number(raw);
    if (Number.isFinite(parsed) && parsed >= 10 && parsed <= 1000) {
      return Math.round(parsed);
    }
  }
  if (process.env.DEMO_MODE === "true" || process.env.COLLEGE_DEMO === "true") {
    return 100;
  }
  return null;
}

function getEffectiveGeofenceRadius() {
  const override = parseServerGeofenceOverride();
  if (override !== null) {
    return override;
  }
  if (isDemoMode) {
    return 100;
  }
  return 50;
}

module.exports = {
  // Server-enforced lifetime of each individual QR token
  TOKEN_VALIDITY_MS: 15000, // 15 seconds

  // Web portal QR rotation cadence (aligned with 15s validity window)
  QR_REFRESH_INTERVAL_MS: 15000, // 15 seconds

  // Max verification attempts permitted per student per session (1 initial + 1 single retry)
  MAX_ATTEMPTS: 2,

  // Geofence radius configurations (100m for demo / local emulator, 50m for production)
  PRODUCTION_GEOFENCE_RADIUS_METERS: 50,
  DEMO_GEOFENCE_RADIUS_METERS: 100,

  // Server-controlled override helper
  parseServerGeofenceOverride,
  getEffectiveGeofenceRadius,

  // Maximum allowed distance in meters between student and classroom
  get LOCATION_THRESHOLD_METERS() {
    return getEffectiveGeofenceRadius();
  },

  // Environment indicator
  isDemoMode
};
