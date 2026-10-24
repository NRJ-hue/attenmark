package com.attendmark.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
    NOT_CONFIGURED
}

data class GpsEvaluationResult(
    val status: GpsVerificationStatus,
    val distanceMeters: Float,
    val accuracyMeters: Float,
    val errorMessage: String? = null
) {
    val isEligibleForRetry: Boolean get() = (status == GpsVerificationStatus.UNCERTAIN_ACCURACY || status == GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR)
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
        const val DEFAULT_GEOFENCE_RADIUS_METERS = 50f
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
         * Evaluates proximity with explicit coordinates and accuracy.
         */
        fun evaluateProximity(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float?,
            classroomLat: Double,
            classroomLon: Double,
            geofenceRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS,
            maxAcceptableAccuracyMeters: Float = MAX_ACCEPTABLE_ACCURACY_METERS
        ): GpsEvaluationResult {
            if (classroomLat == 0.0 && classroomLon == 0.0) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.NOT_CONFIGURED,
                    distanceMeters = 0f,
                    accuracyMeters = 0f
                )
            }

            val distance = calculateDistance(
                latitude,
                longitude,
                classroomLat,
                classroomLon
            )

            val accuracy = accuracyMeters ?: 0f

            return when {
                // Clearly outside geofence
                distance > geofenceRadiusMeters -> {
                    GpsEvaluationResult(
                        status = GpsVerificationStatus.OUTSIDE_GEOFENCE,
                        distanceMeters = distance,
                        accuracyMeters = accuracy,
                        errorMessage = "Location mismatch: You are ${distance.toInt()}m from the classroom (max allowed: ${geofenceRadiusMeters.toInt()}m)."
                    )
                }

                // Inside distance boundary, but GPS signal has poor accuracy (> 50m uncertainty)
                accuracy > maxAcceptableAccuracyMeters -> {
                    GpsEvaluationResult(
                        status = GpsVerificationStatus.UNCERTAIN_ACCURACY,
                        distanceMeters = distance,
                        accuracyMeters = accuracy,
                        errorMessage = "Low GPS accuracy (±${accuracy.toInt()}m). Proximity cannot be verified with certainty."
                    )
                }

                // Confirmed within geofence with good accuracy
                else -> {
                    GpsEvaluationResult(
                        status = GpsVerificationStatus.PASSED,
                        distanceMeters = distance,
                        accuracyMeters = accuracy
                    )
                }
            }
        }

        /**
         * Pure function to evaluate device location against the classroom coordinates.
         * Distinguishes:
         * 1. Confirmed presence (within geofence with acceptable accuracy).
         * 2. High uncertainty (distance within geofence, but GPS error circle exceeds threshold).
         * 3. Outside geofence (distance clearly exceeds 50m).
         * 4. Hardware/signal failure (GPS disabled or no fix).
         * 5. Unconfigured session coordinates (lat/lon = 0.0).
         */
        fun evaluateProximity(
            location: Location?,
            locationError: String?,
            classroomLat: Double,
            classroomLon: Double,
            geofenceRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS,
            maxAcceptableAccuracyMeters: Float = MAX_ACCEPTABLE_ACCURACY_METERS
        ): GpsEvaluationResult {
            // If session coordinates are not set (e.g. 0.0, 0.0), proximity check is bypassed
            if (classroomLat == 0.0 && classroomLon == 0.0) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.NOT_CONFIGURED,
                    distanceMeters = 0f,
                    accuracyMeters = 0f
                )
            }

            if (location == null) {
                return GpsEvaluationResult(
                    status = GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR,
                    distanceMeters = -1f,
                    accuracyMeters = -1f,
                    errorMessage = locationError ?: "Could not determine GPS coordinates. Ensure Location is enabled."
                )
            }

            val accuracy = if (location.hasAccuracy()) location.accuracy else null

            return evaluateProximity(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = accuracy,
                classroomLat = classroomLat,
                classroomLon = classroomLon,
                geofenceRadiusMeters = geofenceRadiusMeters,
                maxAcceptableAccuracyMeters = maxAcceptableAccuracyMeters
            )
        }
    }
}
