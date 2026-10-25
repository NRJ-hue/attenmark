package com.attendmark.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.attendmark.app.BuildConfig
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

/**
 * Result status for classroom GPS proximity verification.
 */
enum class GpsVerificationStatus {
    PASSED,
    UNCERTAIN_ACCURACY,
    OUTSIDE_GEOFENCE,
    HARDWARE_OR_SIGNAL_ERROR,
    MISSING_OR_INVALID_COORDINATES
}

data class GpsEvaluationResult(
    val status: GpsVerificationStatus,
    val distanceMeters: Float,
    val accuracyMeters: Float,
    val errorMessage: String? = null
) {
    val isEligibleForRetry: Boolean get() = (status == GpsVerificationStatus.UNCERTAIN_ACCURACY || status == GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR)
}

data class ClassroomLocation(
    val latitude: Double?,
    val longitude: Double?,
    val isConfigured: Boolean = true,
    val geofenceRadiusMeters: Float = LocationService.DEFAULT_GEOFENCE_RADIUS_METERS
) {
    val isValid: Boolean get() {
        if (!isConfigured || latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN() || geofenceRadiusMeters.isNaN() || geofenceRadiusMeters <= 0f) return false
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) return false
        if (Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001) return false
        return true
    }
}

class LocationService(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Gets the current high-accuracy location. Assumes permissions have already been granted.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Pair<Location?, String?> {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) && 
            !locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            return Pair(null, "Your phone's Location/GPS is completely turned OFF in settings.")
        }
        
        return try {
            val loc = fusedLocationClient.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).await() ?: fusedLocationClient.lastLocation.await()
            
            if (loc != null) Pair(loc, null) else Pair(null, "Could not fetch GPS signal (Try opening Google Maps to refresh)")
        } catch (e: Exception) {
            Pair(null, e.message ?: "Unknown Location error")
        }
    }

    companion object {
        const val PRODUCTION_GEOFENCE_RADIUS_METERS = 50f
        const val DEMO_GEOFENCE_RADIUS_METERS = 100f

        /**
         * Environment-specific default geofence radius.
         * Evaluates to 100m in local development / demo mode (when BuildConfig.DEBUG and emulator is enabled),
         * and strictly 50m in production / release builds.
         */
        val DEFAULT_GEOFENCE_RADIUS_METERS: Float
            get() = if (BuildConfig.DEBUG && FirebaseEmulatorConfig.isEmulatorEnabled) {
                DEMO_GEOFENCE_RADIUS_METERS
            } else {
                PRODUCTION_GEOFENCE_RADIUS_METERS
            }

        fun getActiveDefaultGeofenceRadius(): Float = DEFAULT_GEOFENCE_RADIUS_METERS

        const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f

        /**
         * Calculates distance in meters between two GPS coordinates using the Haversine formula.
         * Pure calculation independent of device framework stubs.
         */
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val earthRadius = 6371000.0 // meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return (earthRadius * c).toFloat()
        }

        /**
         * Validates if classroom coordinates are configured and fall within valid physical bounds.
         * Rejects unset (0.0, 0.0), NaN, or out-of-range values.
         */
        fun isClassroomCoordinatesValid(lat: Double, lon: Double): Boolean {
            return ClassroomLocation(lat, lon, isConfigured = true).isValid
        }

        /**
         * Core decision logic for proximity evaluation given measured distance,
         * reported horizontal accuracy, and configured geofence radius.
         *
         * Mathematical interval / circle classification:
         * - If d + a <= r -> PASSED (confidently inside geofence)
         * - If d - a > r -> OUTSIDE_GEOFENCE (confidently outside geofence)
         * - Otherwise -> UNCERTAIN_ACCURACY (uncertainty circle overlaps geofence boundary)
         *
         * Rejects missing, negative, NaN, or infinite accuracy values with HARDWARE_OR_SIGNAL_ERROR.
         */
        fun evaluateProximity(
            distanceMeters: Float,
            accuracyMeters: Float?,
            geofenceRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS
        ): GpsEvaluationResult {
            if (distanceMeters.isNaN() || distanceMeters < 0f || distanceMeters.isInfinite() ||
                geofenceRadiusMeters.isNaN() || geofenceRadiusMeters <= 0f || geofenceRadiusMeters.isInfinite()
            ) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES,
                    distanceMeters = -1f,
                    accuracyMeters = -1f,
                    errorMessage = "Invalid distance or geofence configuration."
                )
            }

            // Reject / classify as unavailable (HARDWARE_OR_SIGNAL_ERROR) any missing, negative, NaN, or infinite accuracy values
            if (accuracyMeters == null || accuracyMeters.isNaN() || accuracyMeters.isInfinite() || accuracyMeters < 0f) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR,
                    distanceMeters = distanceMeters,
                    accuracyMeters = -1f,
                    errorMessage = "GPS accuracy reported by device is invalid or unavailable."
                )
            }

            val d = distanceMeters
            val a = accuracyMeters
            val r = geofenceRadiusMeters

            return when {
                // Entire uncertainty circle is within geofence: confidently inside
                d + a <= r -> {
                    GpsEvaluationResult(
                        status = GpsVerificationStatus.PASSED,
                        distanceMeters = d,
                        accuracyMeters = a
                    )
                }

                // Entire uncertainty circle is outside geofence: confidently outside
                d - a > r -> {
                    GpsEvaluationResult(
                        status = GpsVerificationStatus.OUTSIDE_GEOFENCE,
                        distanceMeters = d,
                        accuracyMeters = a,
                        errorMessage = "Location mismatch: You are ${d.toInt()}m from the classroom (outside permitted geofence of ${r.toInt()}m accounting for ±${a.toInt()}m accuracy)."
                    )
                }

                // Uncertainty circle overlaps the geofence boundary
                else -> {
                    GpsEvaluationResult(
                        status = GpsVerificationStatus.UNCERTAIN_ACCURACY,
                        distanceMeters = d,
                        accuracyMeters = a,
                        errorMessage = "GPS position is uncertain: distance is ${d.toInt()}m with ±${a.toInt()}m accuracy for a ${r.toInt()}m geofence."
                    )
                }
            }
        }

        /**
         * Evaluates proximity with explicit ClassroomLocation structure.
         */
        fun evaluateProximity(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float?,
            classroomLocation: ClassroomLocation
        ): GpsEvaluationResult {
            if (!classroomLocation.isValid) {
                val detail = when {
                    !classroomLocation.isConfigured || classroomLocation.latitude == null || classroomLocation.longitude == null ->
                        "Classroom location is not configured on this session."
                    classroomLocation.latitude.isNaN() || classroomLocation.longitude.isNaN() ->
                        "Classroom coordinates are invalid (NaN)."
                    classroomLocation.latitude < -90.0 || classroomLocation.latitude > 90.0 || classroomLocation.longitude < -180.0 || classroomLocation.longitude > 180.0 ->
                        "Classroom coordinates (${classroomLocation.latitude}, ${classroomLocation.longitude}) exceed valid geographic bounds."
                    Math.abs(classroomLocation.latitude) < 0.000001 && Math.abs(classroomLocation.longitude) < 0.000001 ->
                        "Classroom coordinates cannot be unset (0.0, 0.0) or missing."
                    classroomLocation.geofenceRadiusMeters.isNaN() || classroomLocation.geofenceRadiusMeters <= 0f ->
                        "Geofence radius must be a positive number."
                    else -> "Classroom location is invalid."
                }
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES,
                    distanceMeters = -1f,
                    accuracyMeters = -1f,
                    errorMessage = detail
                )
            }

            if (latitude.isNaN() || longitude.isNaN() || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR,
                    distanceMeters = -1f,
                    accuracyMeters = -1f,
                    errorMessage = "Device GPS coordinates are invalid or out of bounds."
                )
            }

            val distance = calculateDistance(
                latitude,
                longitude,
                classroomLocation.latitude!!,
                classroomLocation.longitude!!
            )

            return evaluateProximity(
                distanceMeters = distance,
                accuracyMeters = accuracyMeters,
                geofenceRadiusMeters = classroomLocation.geofenceRadiusMeters
            )
        }

        /**
         * Evaluates proximity with explicit coordinates and accuracy.
         */
        fun evaluateProximity(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float?,
            classroomLat: Double?,
            classroomLon: Double?,
            isConfigured: Boolean = true,
            geofenceRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS,
            maxAcceptableAccuracyMeters: Float = MAX_ACCEPTABLE_ACCURACY_METERS
        ): GpsEvaluationResult {
            return evaluateProximity(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
                classroomLocation = ClassroomLocation(
                    latitude = classroomLat,
                    longitude = classroomLon,
                    isConfigured = isConfigured,
                    geofenceRadiusMeters = geofenceRadiusMeters
                )
            )
        }

        /**
         * Pure function to evaluate device location against the classroom coordinates.
         * Distinguishes:
         * 1. Confirmed presence (within geofence with acceptable accuracy).
         * 2. High uncertainty (distance within geofence, but GPS error circle exceeds threshold).
         * 3. Outside geofence (distance clearly exceeds 50m).
         * 4. Hardware/signal failure (GPS disabled or no fix).
         * 5. Missing or invalid session coordinates.
         */
        fun evaluateProximity(
            location: Location?,
            locationError: String?,
            classroomLat: Double?,
            classroomLon: Double?,
            isConfigured: Boolean = true,
            geofenceRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS,
            maxAcceptableAccuracyMeters: Float = MAX_ACCEPTABLE_ACCURACY_METERS
        ): GpsEvaluationResult {
            val classroomLocation = ClassroomLocation(
                latitude = classroomLat,
                longitude = classroomLon,
                isConfigured = isConfigured,
                geofenceRadiusMeters = geofenceRadiusMeters
            )

            if (!classroomLocation.isValid) {
                return evaluateProximity(0.0, 0.0, null, classroomLocation)
            }

            if (locationError != null && locationError.isNotBlank()) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR,
                    distanceMeters = -1f,
                    accuracyMeters = -1f,
                    errorMessage = locationError
                )
            }

            if (location == null) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR,
                    distanceMeters = -1f,
                    accuracyMeters = -1f,
                    errorMessage = "Could not determine GPS coordinates. Ensure Location is enabled."
                )
            }

            val accuracy = if (location.hasAccuracy() || location.accuracy > 0f) location.accuracy else null

            return evaluateProximity(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = accuracy,
                classroomLocation = classroomLocation
            )
        }
    }
}
