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
 * boundary condition classifications, and retry eligibility.
 *
 * Mathematical interval / circle classification:
 * - If d + a <= r -> PASSED
 * - If d - a > r -> OUTSIDE_GEOFENCE
 * - Otherwise -> UNCERTAIN_ACCURACY
 */
class LocationServiceTest {

    private val classroomLat = 37.7749
    private val classroomLon = -122.4194

    /**
     * Calculates latitude offset in degrees corresponding to [meters] along a meridian
     * using Earth radius 6371000m, matching LocationService.calculateDistance.
     */
    private fun offsetForDistance(meters: Double): Double {
        return (meters / 6371000.0) * (180.0 / Math.PI)
    }

    private class FakeLocation(
        private val lat: Double,
        private val lon: Double,
        private val acc: Float?
    ) : Location("mock_provider") {
        override fun getLatitude(): Double = lat
        override fun getLongitude(): Double = lon
        override fun getAccuracy(): Float = acc ?: 0f
        override fun hasAccuracy(): Boolean = acc != null
    }

    private fun createMockLocation(latitude: Double, longitude: Double, accuracyMeters: Float? = null): Location {
        return FakeLocation(latitude, longitude, accuracyMeters)
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
        val studentLat = classroomLat + offsetForDistance(8.9)
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

        // Invalid geofence radius
        val resultInvalidRadius = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = 10f,
            classroomLocation = ClassroomLocation(
                latitude = classroomLat,
                longitude = classroomLon,
                geofenceRadiusMeters = -10f
            )
        )
        assertEquals(GpsVerificationStatus.MISSING_OR_INVALID_COORDINATES, resultInvalidRadius.status)
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

    // =========================================================================
    // Boundary Condition Tests (d + a <= r -> PASSED, d - a > r -> OUTSIDE, else UNCERTAIN)
    // =========================================================================

    @Test
    fun proximity_confidentlyInsideGeofencePasses() {
        // d = 20m, a = 10m, r = 50m -> d + a = 30m <= 50m -> PASSED
        val studentLat = classroomLat + offsetForDistance(20.0)
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
        assertEquals(20f, result.distanceMeters, 0.5f)
        assertEquals(10f, result.accuracyMeters, 0.01f)
        assertNull(result.errorMessage)
    }

    @Test
    fun proximity_exactInnerBoundaryPasses() {
        // Exact inner boundary: d = 35m, a = 15m, r = 50m -> d + a = 50m <= 50m -> PASSED
        val studentLat = classroomLat + offsetForDistance(35.0)
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 15f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.PASSED, result.status)
        assertEquals(35f, result.distanceMeters, 0.5f)
        assertEquals(15f, result.accuracyMeters, 0.01f)
        assertNull(result.errorMessage)
    }

    @Test
    fun proximity_measuredInsideOverlappingRadiusIsUncertain() {
        // Measured distance is inside (d = 45m < 50m), but with a = 15m:
        // d + a = 60m > 50m and d - a = 30m <= 50m -> UNCERTAIN_ACCURACY
        val studentLat = classroomLat + offsetForDistance(45.0)
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 15f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, result.status)
        assertTrue(result.isEligibleForRetry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("uncertain") == true)
        assertEquals(45f, result.distanceMeters, 0.5f)
    }

    @Test
    fun proximity_measuredOutsideOverlappingRadiusIsUncertain() {
        // Measured distance is slightly outside (d = 55m > 50m), but with a = 15m:
        // d - a = 40m <= 50m and d + a = 70m > 50m -> UNCERTAIN_ACCURACY
        // Previously this was prematurely rejected as OUTSIDE_GEOFENCE; now properly classified as UNCERTAIN_ACCURACY
        val studentLat = classroomLat + offsetForDistance(55.0)
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 15f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, result.status)
        assertTrue(result.isEligibleForRetry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("uncertain") == true)
        assertEquals(55f, result.distanceMeters, 0.5f)
    }

    @Test
    fun proximity_reportedProductionDistance35mWith20mAccuracyIsUncertain() {
        // Concrete reported issue: distance = 35m, accuracy = ±20m, geofence = 50m
        // d + a = 35 + 20 = 55m > 50m
        // d - a = 35 - 20 = 15m <= 50m
        // Must evaluate to UNCERTAIN_ACCURACY (not OUTSIDE_GEOFENCE or PASSED)
        val studentLat = classroomLat + offsetForDistance(35.0)
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 20f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, result.status)
        assertTrue(result.isEligibleForRetry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("uncertain") == true)
        assertEquals(35f, result.distanceMeters, 0.5f)
        assertEquals(20f, result.accuracyMeters, 0.01f)
    }

    @Test
    fun proximity_exactOuterBoundaryIsUncertain() {
        // Exact outer boundary: d = 65m, a = 15m, r = 50m -> d - a = 50m.
        // Since (d - a > r) is false (50 > 50 is false), uncertainty circle touches boundary -> UNCERTAIN_ACCURACY
        val studentLat = classroomLat + offsetForDistance(65.0)
        val studentLon = classroomLon

        val result = LocationService.evaluateProximity(
            latitude = studentLat,
            longitude = studentLon,
            accuracyMeters = 15f,
            classroomLat = classroomLat,
            classroomLon = classroomLon,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, result.status)
        assertTrue(result.isEligibleForRetry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("uncertain") == true)
    }

    @Test
    fun proximity_confidentlyOutsideGeofenceFailsWithoutRetry() {
        // Confidently outside: d = 80m, a = 15m, r = 50m -> d - a = 65m > 50m -> OUTSIDE_GEOFENCE
        val studentLat = classroomLat + offsetForDistance(80.0)
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
        assertEquals(80f, result.distanceMeters, 0.5f)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage?.contains("Location mismatch") == true)
        assertFalse("Outside geofence violations cannot be retried", result.isEligibleForRetry)
    }

    // =========================================================================
    // Accuracy Sanity & Error Classification Tests
    // Reject / classify as unavailable (HARDWARE_OR_SIGNAL_ERROR) any missing,
    // negative, NaN, or infinite accuracy values. Never produce an automatic pass.
    // =========================================================================

    @Test
    fun proximity_nullAccuracyRejectedAsHardwareOrSignalError() {
        val result = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = null,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, result.status)
        assertEquals(-1f, result.accuracyMeters)
        assertTrue(result.isEligibleForRetry)
        assertTrue(result.errorMessage?.contains("GPS accuracy") == true)
    }

    @Test
    fun proximity_negativeAccuracyRejectedAsHardwareOrSignalError() {
        val result = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = -5f,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, result.status)
        assertEquals(-1f, result.accuracyMeters)
        assertTrue(result.isEligibleForRetry)
        assertTrue(result.errorMessage?.contains("GPS accuracy") == true)
    }

    @Test
    fun proximity_nanAccuracyRejectedAsHardwareOrSignalError() {
        val result = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = Float.NaN,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, result.status)
        assertEquals(-1f, result.accuracyMeters)
        assertTrue(result.isEligibleForRetry)
        assertTrue(result.errorMessage?.contains("GPS accuracy") == true)
    }

    @Test
    fun proximity_infiniteAccuracyRejectedAsHardwareOrSignalError() {
        val resultPositive = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = Float.POSITIVE_INFINITY,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, resultPositive.status)
        assertTrue(resultPositive.isEligibleForRetry)

        val resultNegative = LocationService.evaluateProximity(
            latitude = classroomLat,
            longitude = classroomLon,
            accuracyMeters = Float.NEGATIVE_INFINITY,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, resultNegative.status)
        assertTrue(resultNegative.isEligibleForRetry)
    }

    @Test
    fun proximity_locationObjectWithoutAccuracyRejectedAsSignalError() {
        val locWithoutAccuracy = Location("mock_gps_provider").apply {
            latitude = classroomLat
            longitude = classroomLon
            // accuracy not set -> hasAccuracy() is false
        }

        val result = LocationService.evaluateProximity(
            location = locWithoutAccuracy,
            locationError = null,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, result.status)
        assertEquals(-1f, result.accuracyMeters)
        assertTrue(result.isEligibleForRetry)
    }

    @Test
    fun proximity_locationObjectWithGoodAccuracyPasses() {
        val loc = createMockLocation(
            latitude = classroomLat + offsetForDistance(20.0),
            longitude = classroomLon,
            accuracyMeters = 10f
        )

        val result = LocationService.evaluateProximity(
            location = loc,
            locationError = null,
            classroomLat = classroomLat,
            classroomLon = classroomLon
        )

        assertEquals(GpsVerificationStatus.PASSED, result.status)
        assertEquals(20f, result.distanceMeters, 0.5f)
        assertEquals(10f, result.accuracyMeters, 0.01f)
    }

    @Test
    fun proximity_directDistanceEvaluationMatchesIntervalRules() {
        // d + a <= r -> PASSED
        val passResult = LocationService.evaluateProximity(distanceMeters = 30f, accuracyMeters = 10f, geofenceRadiusMeters = 50f)
        assertEquals(GpsVerificationStatus.PASSED, passResult.status)

        // d - a > r -> OUTSIDE_GEOFENCE
        val outsideResult = LocationService.evaluateProximity(distanceMeters = 75f, accuracyMeters = 15f, geofenceRadiusMeters = 50f)
        assertEquals(GpsVerificationStatus.OUTSIDE_GEOFENCE, outsideResult.status)
        assertFalse(outsideResult.isEligibleForRetry)

        // d + a > r and d - a <= r -> UNCERTAIN_ACCURACY
        val uncertainResult = LocationService.evaluateProximity(distanceMeters = 55f, accuracyMeters = 15f, geofenceRadiusMeters = 50f)
        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, uncertainResult.status)
        assertTrue(uncertainResult.isEligibleForRetry)

        // Invalid accuracy -> HARDWARE_OR_SIGNAL_ERROR
        val invalidResult = LocationService.evaluateProximity(distanceMeters = 20f, accuracyMeters = -1f, geofenceRadiusMeters = 50f)
        assertEquals(GpsVerificationStatus.HARDWARE_OR_SIGNAL_ERROR, invalidResult.status)
        assertTrue(invalidResult.isEligibleForRetry)
    }

    // --- Environment-Specific Geofence Tests ------------------------------------

    @Test
    fun proximity_demoGpsReadingAtFiftyMetersIsUncertainAccuracy() {
        // Physical device test scenario: Student phone beside laptop reports d = 36m, a = ±28m.
        // Under production 50m geofence:
        // d + a = 36 + 28 = 64m > 50m (uncertainty circle extends beyond geofence)
        // d - a = 36 - 28 = 8m <= 50m (not confidently outside)
        // -> UNCERTAIN_ACCURACY
        val result = LocationService.evaluateProximity(
            distanceMeters = 36f,
            accuracyMeters = 28f,
            geofenceRadiusMeters = 50f
        )

        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, result.status)
        assertEquals(36f, result.distanceMeters, 0.01f)
        assertEquals(28f, result.accuracyMeters, 0.01f)
        assertTrue("Uncertain accuracy readings are eligible for retry", result.isEligibleForRetry)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("36m"))
        assertTrue(result.errorMessage!!.contains("28m"))
        assertTrue(result.errorMessage!!.contains("50m"))
    }

    @Test
    fun proximity_demoGpsReadingAtOneHundredMetersPasses() {
        // Same physical device reading (d = 36m, a = ±28m) under demo/local 100m geofence:
        // d + a = 36 + 28 = 64m <= 100m (entire uncertainty circle is confidently inside geofence)
        // -> PASSED
        val result = LocationService.evaluateProximity(
            distanceMeters = 36f,
            accuracyMeters = 28f,
            geofenceRadiusMeters = 100f
        )

        assertEquals(GpsVerificationStatus.PASSED, result.status)
        assertEquals(36f, result.distanceMeters, 0.01f)
        assertEquals(28f, result.accuracyMeters, 0.01f)
        assertNull(result.errorMessage)
    }

    @Test
    fun proximity_oneHundredMeterGeofence_boundaryConditions() {
        // 1. Confidently inside at boundary: d = 70m, a = 30m, r = 100m -> d + a = 100m <= 100m -> PASSED
        val insideBoundary = LocationService.evaluateProximity(
            distanceMeters = 70f,
            accuracyMeters = 30f,
            geofenceRadiusMeters = 100f
        )
        assertEquals(GpsVerificationStatus.PASSED, insideBoundary.status)

        // 2. Inside distance, but uncertainty crosses boundary: d = 80m, a = 30m, r = 100m -> d + a = 110m > 100m, d - a = 50m <= 100m -> UNCERTAIN
        val overlapInner = LocationService.evaluateProximity(
            distanceMeters = 80f,
            accuracyMeters = 30f,
            geofenceRadiusMeters = 100f
        )
        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, overlapInner.status)
        assertTrue(overlapInner.isEligibleForRetry)

        // 3. Outside distance, but uncertainty circles geofence: d = 110m, a = 20m, r = 100m -> d - a = 90m <= 100m -> UNCERTAIN
        val overlapOuter = LocationService.evaluateProximity(
            distanceMeters = 110f,
            accuracyMeters = 20f,
            geofenceRadiusMeters = 100f
        )
        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, overlapOuter.status)
        assertTrue(overlapOuter.isEligibleForRetry)

        // 4. Exact outer boundary: d = 120m, a = 20m, r = 100m -> d - a = 100m <= 100m -> UNCERTAIN
        val exactOuter = LocationService.evaluateProximity(
            distanceMeters = 120f,
            accuracyMeters = 20f,
            geofenceRadiusMeters = 100f
        )
        assertEquals(GpsVerificationStatus.UNCERTAIN_ACCURACY, exactOuter.status)
        assertTrue(exactOuter.isEligibleForRetry)

        // 5. Confidently outside boundary: d = 125m, a = 20m, r = 100m -> d - a = 105m > 100m -> OUTSIDE_GEOFENCE
        val outsideBoundary = LocationService.evaluateProximity(
            distanceMeters = 125f,
            accuracyMeters = 20f,
            geofenceRadiusMeters = 100f
        )
        assertEquals(GpsVerificationStatus.OUTSIDE_GEOFENCE, outsideBoundary.status)
        assertFalse("Outside geofence violations cannot be retried", outsideBoundary.isEligibleForRetry)

        // 6. Confidently outside far away: d = 140m, a = 30m, r = 100m -> d - a = 110m > 100m -> OUTSIDE_GEOFENCE
        val farOutside = LocationService.evaluateProximity(
            distanceMeters = 140f,
            accuracyMeters = 30f,
            geofenceRadiusMeters = 100f
        )
        assertEquals(GpsVerificationStatus.OUTSIDE_GEOFENCE, farOutside.status)
        assertFalse(farOutside.isEligibleForRetry)
        assertTrue(farOutside.errorMessage!!.contains("100m"))
    }

    @Test
    fun proximity_environmentSpecificDefaultRadiusResolution() {
        assertEquals(50f, LocationService.PRODUCTION_GEOFENCE_RADIUS_METERS, 0.01f)
        assertEquals(100f, LocationService.DEMO_GEOFENCE_RADIUS_METERS, 0.01f)

        val initialEmulatorState = FirebaseEmulatorConfig.isEmulatorEnabled
        try {
            // When emulator mode is enabled (local development/demo), default radius is 100m
            FirebaseEmulatorConfig.isEmulatorEnabled = true
            assertEquals(100f, LocationService.DEFAULT_GEOFENCE_RADIUS_METERS, 0.01f)
            assertEquals(100f, LocationService.getActiveDefaultGeofenceRadius(), 0.01f)

            // When emulator mode is disabled (production configuration), default radius is 50m
            FirebaseEmulatorConfig.isEmulatorEnabled = false
            assertEquals(50f, LocationService.DEFAULT_GEOFENCE_RADIUS_METERS, 0.01f)
            assertEquals(50f, LocationService.getActiveDefaultGeofenceRadius(), 0.01f)
        } finally {
            FirebaseEmulatorConfig.isEmulatorEnabled = initialEmulatorState
        }
    }
}
