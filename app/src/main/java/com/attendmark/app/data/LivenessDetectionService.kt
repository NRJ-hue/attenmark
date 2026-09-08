package com.attendmark.app.data

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Liveness detection service using ML Kit Face Detection.
 *
 * Liveness challenge types:
 *  - BLINK: Detect if eyes are closed (blink detected)
 *  - SMILE: Detect if smiling
 *  - TURN_LEFT: Head turned left (yaw < -15 degrees)
 *  - TURN_RIGHT: Head turned right (yaw > +15 degrees)
 *  - LOOK_UP: Head tilted up (pitch < -15 degrees)
 *
 * For each challenge, the app:
 *  1. Shows the challenge instruction
 *  2. Continuously analyzes camera frames
 *  3. Detects when the action is performed
 *  4. Marks challenge as passed
 */
class LivenessDetectionService {

    companion object {
        private const val TAG = "LivenessService"

        // Thresholds
        private const val EYE_OPEN_PROBABILITY_THRESHOLD = 0.4f  // below = closed (increased to make blinking easier to detect)
        private const val SMILE_PROBABILITY_THRESHOLD = 0.4f     // lowered from 0.7 to 0.4
        private const val HEAD_TURN_THRESHOLD = 10f // lowered from 15 to 10 degrees
        private const val HEAD_TILT_THRESHOLD = 10f
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // enables smile/eye
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    enum class LivenessChallenge(val instruction: String, val emoji: String) {
        BLINK("Blink your eyes", "👁️"),
        SMILE("Smile for the camera", "😊"),
        TURN_LEFT("Turn your head LEFT", "👈"),
        TURN_RIGHT("Turn your head RIGHT", "👉"),
    }

    data class LivenessResult(
        val faceDetected: Boolean,
        val challengePassed: Boolean,
        val multipleFaces: Boolean = false,
        val noFace: Boolean = false,
        val faceRect: Rect? = null,
        val faceBitmap: Bitmap? = null  // cropped face region for embedding
    )

    /**
     * Pick a random liveness challenge for the session.
     */
    fun randomChallenge(): LivenessChallenge {
        return LivenessChallenge.values().random()
    }

    /**
     * Analyze a bitmap frame for liveness challenge completion.
     * Returns LivenessResult with challenge pass/fail.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        challenge: LivenessChallenge,
        isFrontCamera: Boolean = true
    ): LivenessResult = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                when {
                    faces.isEmpty() -> cont.resume(
                        LivenessResult(faceDetected = false, challengePassed = false, noFace = true)
                    )
                    faces.size > 1 -> cont.resume(
                        LivenessResult(faceDetected = true, challengePassed = false, multipleFaces = true)
                    )
                    else -> {
                        val face = faces[0]
                        val passed = checkChallenge(face, challenge, isFrontCamera)
                        val croppedFace = cropFaceFromBitmap(bitmap, face.boundingBox)
                        cont.resume(
                            LivenessResult(
                                faceDetected = true,
                                challengePassed = passed,
                                faceRect = face.boundingBox,
                                faceBitmap = croppedFace
                            )
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}")
                cont.resume(LivenessResult(faceDetected = false, challengePassed = false))
            }
    }

    /**
     * Just detect if a face is present (used for enrollment).
     */
    suspend fun detectFace(bitmap: Bitmap): LivenessResult = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                when {
                    faces.isEmpty() -> cont.resume(
                        LivenessResult(faceDetected = false, challengePassed = false, noFace = true)
                    )
                    faces.size > 1 -> cont.resume(
                        LivenessResult(faceDetected = true, challengePassed = false, multipleFaces = true)
                    )
                    else -> {
                        val face = faces[0]
                        val croppedFace = cropFaceFromBitmap(bitmap, face.boundingBox)
                        cont.resume(
                            LivenessResult(
                                faceDetected = true,
                                challengePassed = true,
                                faceRect = face.boundingBox,
                                faceBitmap = croppedFace
                            )
                        )
                    }
                }
            }
            .addOnFailureListener {
                cont.resume(LivenessResult(faceDetected = false, challengePassed = false))
            }
    }

    private fun checkChallenge(face: Face, challenge: LivenessChallenge, isFrontCamera: Boolean): Boolean {
        return when (challenge) {
            LivenessChallenge.BLINK -> {
                val leftEye = face.leftEyeOpenProbability ?: return false
                val rightEye = face.rightEyeOpenProbability ?: return false
                // Both eyes closed = blink
                leftEye < EYE_OPEN_PROBABILITY_THRESHOLD && rightEye < EYE_OPEN_PROBABILITY_THRESHOLD
            }
            LivenessChallenge.SMILE -> {
                val smile = face.smilingProbability ?: return false
                smile >= SMILE_PROBABILITY_THRESHOLD
            }
            LivenessChallenge.TURN_LEFT -> {
                val yaw = face.headEulerAngleY
                // Front camera mirrors, so left/right are flipped for the actual person
                if (isFrontCamera) yaw > HEAD_TURN_THRESHOLD else yaw < -HEAD_TURN_THRESHOLD
            }
            LivenessChallenge.TURN_RIGHT -> {
                val yaw = face.headEulerAngleY
                if (isFrontCamera) yaw < -HEAD_TURN_THRESHOLD else yaw > HEAD_TURN_THRESHOLD
            }
        }
    }

    /**
     * Crop the face bounding box from the bitmap, with some padding.
     */
    private fun cropFaceFromBitmap(bitmap: Bitmap, rect: Rect): Bitmap? {
        return try {
            val padding = (rect.width() * 0.15f).toInt()
            val left = (rect.left - padding).coerceAtLeast(0)
            val top = (rect.top - padding).coerceAtLeast(0)
            val right = (rect.right + padding).coerceAtMost(bitmap.width)
            val bottom = (rect.bottom + padding).coerceAtMost(bitmap.height)

            if (right > left && bottom > top) {
                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop face: ${e.message}")
            null
        }
    }

    fun close() {
        detector.close()
    }
}
