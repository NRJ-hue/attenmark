/**
 * AttendIQ Cloud Functions Configuration
 *
 * Central configuration constants for QR token validity, refresh rates,
 * and retry thresholds.
 */
module.exports = {
  // Server-enforced lifetime of each individual QR token
  TOKEN_VALIDITY_MS: 30000, // 30 seconds

  // Web portal QR rotation cadence (generates next token before current expires)
  QR_REFRESH_INTERVAL_MS: 25000, // 25 seconds

  // Max verification attempts permitted per student per session (1 initial + 1 single retry)
  MAX_ATTEMPTS: 2,

  // Maximum allowed distance in meters between student and classroom
  LOCATION_THRESHOLD_METERS: 50
};
