package com.attendmark.app.ui.screens.student

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX ImageAnalysis analyzer that feeds frames to Google ML Kit Barcode Scanning.
 * Configured specifically for QR codes with robust single-scan concurrency protection.
 */
class QrCodeAnalyzer(
    private val scannerClient: BarcodeScanner? = null,
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val isPaused = AtomicBoolean(false)

    private val scanner: BarcodeScanner by lazy {
        scannerClient ?: run {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            BarcodeScanning.getClient(options)
        }
    }

    /**
     * Pauses frame processing.
     * Subsequent frames are immediately dropped and closed without running ML Kit.
     */
    fun pauseScanning() {
        isPaused.set(true)
    }

    /**
     * Resumes frame processing for a new scan attempt.
     */
    fun resumeScanning() {
        isPaused.set(false)
    }

    /**
     * Check whether analysis is currently paused.
     */
    fun isScanningPaused(): Boolean = isPaused.get()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // If analysis is paused (e.g., validation in-flight, error displayed, or scan succeeded),
        // release the CameraX image buffer immediately without invoking ML Kit.
        if (isPaused.get()) {
            imageProxy.close()
            return
        }

        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (isPaused.get()) return@addOnSuccessListener

                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            // Atomically pause on first valid detection to prevent duplicate frame dispatch
                            if (isPaused.compareAndSet(false, true)) {
                                onQrCodeScanned(rawValue)
                            }
                            break
                        }
                    }
                }
                .addOnFailureListener {
                    // Ignore transient analysis frame failures
                }
                .addOnCompleteListener {
                    try {
                        imageProxy.close()
                    } catch (_: Exception) {}
                }
        } catch (e: Exception) {
            try {
                imageProxy.close()
            } catch (_: Exception) {}
        }
    }

    fun close() {
        pauseScanning()
        try {
            scanner.close()
        } catch (_: Exception) {}
    }
}
