package com.attendmark.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await

class LocationService(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Gets the last known location. Assumes permissions have already been granted.
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
        /**
         * Calculates distance in meters between two GPS coordinates.
         */
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }
    }
}
