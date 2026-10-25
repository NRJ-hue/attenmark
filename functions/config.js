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

module.exports = {
  // Server-enforced lifetime of each individual QR token
  TOKEN_VALIDITY_MS: 30000, // 30 seconds

  // Web portal QR rotation cadence (generates next token before current expires)
  QR_REFRESH_INTERVAL_MS: 25000, // 25 seconds

  // Max verification attempts permitted per student per session (1 initial + 1 single retry)
  MAX_ATTEMPTS: 2,

  // Geofence radius configurations (100m for demo / local emulator, 50m for production)
  PRODUCTION_GEOFENCE_RADIUS_METERS: 50,
  DEMO_GEOFENCE_RADIUS_METERS: 100,

  // Maximum allowed distance in meters between student and classroom
  LOCATION_THRESHOLD_METERS: isDemoMode ? 100 : 50,

  // Environment indicator
  isDemoMode
};
