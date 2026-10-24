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
    fun proximity_missingOrUnsetCoordinatesFailsWithoutBypass() {
        // P0: A missing or unset classroom location must never bypass verification or produce a successful result
        val result = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = 10f,
            classroomLat = 0.0,
            classroomLon = 0.0
        )

        assertEquals(GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES, result.status)
        assertEquals(-1f, result.distanceMeters)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("missing") == true || result.errorMessage?.contains("unset") == true)
        assertFalse(result.isEligibleForRetry)
    }

    @Test
    fun proximity_unconfiguredLocationFlagFailsWithoutBypass() {
        // Location coordinates exist, but session has isConfigured = false
        val studentLat = classroomLat + 0.00008
        val studentLon = classroomLon
        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 10f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            isConfigured = false
        )

        assertEquals(GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES, result.status)
        assertEquals(-1f, result.distanceMeters)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("not configured") == true)
        assertFalse(result.isEligibleForRetry)
    }

    @Test
    fun proximity_nullCoordinatesFailsWithoutBypass() {
        // Classroom latitude and longitude are null
        val result = LocationService.evaluateProximity(
            location = null,
            locationError = null,
            classroomLat = null,
            classroomLon = null
        )

        assertEquals(GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES, result.status)
        assertEquals(-1f, result.distanceMeters)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("not configured") == true)
        assertFalse(result.isEligibleForRetry)
    }

    @Test
    fun proximity_invalidCoordinatesFailsWithoutBypass() {
        // P0: Out-of-range coordinates must be rejected
        val resultOutOfRange = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = 10f,
            classroomLat = 95.0,
            classroomLon = -122.4194
        )

        assertEquals(GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES, resultOutOfRange.status)
        assertEquals(-1f, resultOutOfRange.distanceMeters)

        // NaN coordinates
        val resultNaN = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = 10f,
            classroomLat = Double.NaN,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES, resultNaN.status)
        assertEquals(-1f, resultNaN.distanceMeters)
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
