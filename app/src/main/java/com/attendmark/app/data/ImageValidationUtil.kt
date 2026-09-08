package com.attendmark.app.data

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import android.util.Base64

object ImageValidationUtil {

    /**
     * Checks if the camera is likely covered by a hand or against a desk.
     * Uses a mathematical check on average luminance (brightness) and standard deviation (texture/variance).
     * If the image is entirely dark and has very little variation in pixel colors, it's covered.
     * @return true if the image is deemed valid (not covered), false if it looks like a covered camera.
     */
    fun isImageValid(bitmap: Bitmap): Boolean {
        // Sample pixels to save processing time (e.g., check every 10th pixel)
        val step = 10
        val width = bitmap.width
        val height = bitmap.height
        
        var totalLuminance = 0L
        var pixelCount = 0
        
        val luminances = mutableListOf<Double>()

        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Standard relative luminance formula
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                totalLuminance += luminance.toLong()
                luminances.add(luminance)
                pixelCount++
            }
        }

        if (pixelCount == 0) return false

        val avgLuminance = totalLuminance.toDouble() / pixelCount

        // Calculate Standard Deviation (Texture / Variance)
        var sumOfSquaredDifferences = 0.0
        for (lum in luminances) {
            val diff = lum - avgLuminance
            sumOfSquaredDifferences += diff * diff
        }
        
        val variance = sumOfSquaredDifferences / pixelCount
        val standardDeviation = Math.sqrt(variance)

        // Thresholds:
        // A completely covered camera will be pitch black (Luminance near 0) with zero texture (SD near 0).
        // Sometimes light bleeds through fingers (reddish), so luminance might be slightly higher, but texture will still be extremely low.
        val isPitchBlack = avgLuminance < 10.0
        val isTextureless = standardDeviation < 5.0

        if (isPitchBlack || isTextureless) {
            return false // Invalid / Covered
        }

        return true // Valid
    }

    /**
     * Compresses a Bitmap into a Base64 string for Firestore.
     * Keeps the file size small to respect the 1MB Firestore document limit.
     */
    fun compressToBase64(bitmap: Bitmap): String {
        // Downscale to 480p width to save immense space
        val maxWidth = 480
        val scale = maxWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * scale).toInt()
        
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
        
        val outputStream = ByteArrayOutputStream()
        // Compress aggressively (50% quality JPEG is usually fine for verification thumbnails)
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }
}
