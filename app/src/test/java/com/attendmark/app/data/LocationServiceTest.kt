package com.attendmark.app.data

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LocationService GPS uncertainty handling, geofence compliance,
 * and retry eligibility.
 */
class LocationServiceTest {

    private val classroomLat = 37.7749
    private val classroomLon = -122.4194

    private fun createMockLocation(latitude: Double, longitude: Double, accuracyMeters: Float? = null): Location {
        val loc = Location("mock_gps_provider")
        loc.latitude = latitude
        loc.longitude = longitude
        if (accuracyMeters != null) {
            loc.accuracy = accuracyMeters
        }
        return loc
    }

    @Test
    fun proximity_unconfiguredCoordinatesAlwaysPasses() {
        val location = createMockLocation(0.0, 0.0)
        val result = LocationService.evaluateProximity(
            location = location,
            locationError = null,
            classroomLat = 0.0,
            classroomLon = 0.0
        )

        assertEquals(GpsVerificationStatus.NOT_CONFIGURED, result.status)
        assertEquals(0f, result.distanceMeters)
        assertNull(result.errorMessage)
    }

    @Test
    fun proximity_nullLocationDistinguishesHardwareOrSignalFailure() {
        val result = LocationService.evaluateProximity(
            location = null,
            locationError = "Location service is disabled",
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, result.status)
        assertTrue(result.errorMessage?.contains("Location service is disabled") == true)
        assertTrue(result.isEligibleForRetry)
    }

    @Test
    fun proximity_goodAccuracyInsideGeofencePasses() {
        // Location very close (e.g. ~8.9 meters away)
        val studentLat = classroomLat + 0.00008
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 10f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.PASSED, result.status)
        assertTrue(result.distanceMeters <= 50f)
        assertNull(result.errorMessage)
    }

    @Test
    fun proximity_poorAccuracyInsideGeofenceFlagsUncertainty() {
        // Distance is small (~8.9m), but GPS accuracy uncertainty is 80m (poor satellite fix)
        val studentLat = classroomLat + 0.00008
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 80f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f,
            maxAcceptableAccuracyMeters = 50f
        )

        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, result.status)
        assertTrue(result.isEligibleForRetry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("Low GPS accuracy") == true)
    }

    @Test
    fun proximity_clearlyOutsideGeofenceFailsWithoutRetry() {
        // Location 550m away
        val studentLat = classroomLat + 0.005
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 15f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.OUTSIDE_GEOFENCE, result.status)
        assertTrue(result.distanceMeters > 50f)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("Location mismatch") == true)
        assertFalse("Outside geofence violations cannot be retried", result.isEligibleForRetry)
    }
}
