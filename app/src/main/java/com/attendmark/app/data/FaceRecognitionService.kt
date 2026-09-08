package com.attendmark.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face Recognition service using MobileFaceNet TFLite model.
 * Runs entirely on-device — no server needed.
 *
 * The model accepts a 112x112 RGB face image and outputs a 192-dim embedding.
 * We compare embeddings using cosine similarity.
 */
class FaceRecognitionService(private val context: Context) {

    companion object {
        private const val TAG = "FaceRecognitionService"
        private const val MODEL_FILE = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_SIZE = 192
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        // Cosine similarity thresholds
        private const val THRESHOLD_STRONG_MATCH = 0.75f
        private const val THRESHOLD_LIKELY_MATCH = 0.60f
        private const val THRESHOLD_WEAK_MATCH = 0.50f
    }

    private var interpreter: Interpreter? = null
    private val gson = Gson()

    init {
        try {
            loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}")
        }
    }

    private fun loadModel() {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(modelBuffer, options)
        Log.d(TAG, "MobileFaceNet model loaded successfully")
    }

    /**
     * Extract a face embedding from a bitmap.
     * The bitmap should already be cropped to just the face region.
     * Returns a FloatArray of size EMBEDDING_SIZE, or null on failure.
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: run {
            Log.e(TAG, "Interpreter not initialized")
            return null
        }

        return try {
            // Resize to 112x112
            val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)

            // Convert to ByteBuffer (FLOAT32, NCHW: 1 x 3 x 112 x 112 — MobileFaceNet uses NHWC)
            val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            byteBuffer.order(ByteOrder.nativeOrder())

            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            for (pixel in pixels) {
                val r = ((pixel shr 16) and 0xFF)
                val g = ((pixel shr 8) and 0xFF)
                val b = (pixel and 0xFF)
                byteBuffer.putFloat((r - IMAGE_MEAN) / IMAGE_STD)
                byteBuffer.putFloat((g - IMAGE_MEAN) / IMAGE_STD)
                byteBuffer.putFloat((b - IMAGE_MEAN) / IMAGE_STD)
            }

            // Output buffer
            val embeddings = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interp.run(byteBuffer, embeddings)

            // L2-normalize the embedding
            val embedding = embeddings[0]
            val norm = sqrt(embedding.fold(0f) { acc, v -> acc + v * v })
            if (norm > 0) {
                for (i in embedding.indices) embedding[i] /= norm
            }

            Log.d(TAG, "Embedding extracted, norm=$norm")
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting embedding: ${e.message}")
            null
        }
    }

    /**
     * Compute cosine similarity between two L2-normalized embeddings.
     * Returns value between -1 and 1. Higher = more similar.
     */
    fun cosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        if (emb1.size != emb2.size) return 0f
        var dot = 0f
        for (i in emb1.indices) dot += emb1[i] * emb2[i]
        return dot.coerceIn(-1f, 1f)
    }

    /**
     * Returns a human-readable match percentage (0-100) from similarity.
     * Maps cosine similarity to a 0-100% scale for display.
     */
    fun similarityToPercentage(similarity: Float): Int {
        // Map similarity from [0.3, 1.0] to [0%, 100%]
        val clamped = similarity.coerceIn(0f, 1f)
        return (clamped * 100).toInt()
    }

    /**
     * Determine match result from similarity score.
     */
    fun getMatchResult(similarity: Float): MatchResult {
        return when {
            similarity >= THRESHOLD_STRONG_MATCH -> MatchResult.STRONG_MATCH
            similarity >= THRESHOLD_LIKELY_MATCH -> MatchResult.LIKELY_MATCH
            similarity >= THRESHOLD_WEAK_MATCH -> MatchResult.WEAK_MATCH
            else -> MatchResult.NO_MATCH
        }
    }

    // ─── Serialization for DataStore storage ───────────────────────────────────

    fun embeddingToJson(embedding: FloatArray): String {
        return gson.toJson(embedding.toList())
    }

    fun jsonToEmbedding(json: String): FloatArray? {
        return try {
            val listType = object : TypeToken<List<Float>>() {}.type
            val list: List<Float> = gson.fromJson(json, listType)
            list.toFloatArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize embedding: ${e.message}")
            null
        }
    }

    enum class MatchResult(val label: String, val description: String) {
        STRONG_MATCH("STRONG MATCH", "Identity confirmed with high confidence"),
        LIKELY_MATCH("LIKELY MATCH", "Identity likely confirmed"),
        WEAK_MATCH("WEAK MATCH", "Low confidence — may need review"),
        NO_MATCH("NO MATCH", "Face does not match enrolled student")
    }
}
