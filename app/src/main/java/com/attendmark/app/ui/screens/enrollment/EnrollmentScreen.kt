package com.attendmark.app.ui.screens.enrollment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.attendmark.app.data.FaceRecognitionService
import com.attendmark.app.data.LivenessDetectionService
import com.attendmark.app.data.UserPreferences
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

enum class EnrollmentStep { INSTRUCTIONS, CAMERA, PROCESSING, SUCCESS, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScreen(
    onEnrollmentComplete: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferences(context) }
    val faceService = remember { FaceRecognitionService(context) }
    val livenessService = remember { LivenessDetectionService() }

    var step by remember { mutableStateOf(EnrollmentStep.INSTRUCTIONS) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            step = EnrollmentStep.CAMERA
        } else {
            errorMessage = "Camera permission is required for face enrollment."
            step = EnrollmentStep.ERROR
        }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            livenessService.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Face Enrollment", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue),
                actions = {
                    TextButton(onClick = onLogout) {
                        Text("Logout", color = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            when (step) {
                EnrollmentStep.INSTRUCTIONS -> InstructionsStep {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        step = EnrollmentStep.CAMERA
                    } else {
                        permissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                }

                EnrollmentStep.CAMERA -> CameraStep(
                    imageCapture = imageCapture,
                    cameraExecutor = cameraExecutor,
                    onCapture = { bitmap ->
                        capturedBitmap = bitmap
                        step = EnrollmentStep.PROCESSING
                        statusMessage = "Detecting face..."
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                livenessService.detectFace(bitmap)
                            }
                            when {
                                result.noFace -> {
                                    errorMessage = "No face detected. Please try again with better lighting."
                                    step = EnrollmentStep.ERROR
                                }
                                result.multipleFaces -> {
                                    errorMessage = "Multiple faces detected. Please capture only your face."
                                    step = EnrollmentStep.ERROR
                                }
                                result.faceBitmap != null -> {
                                    statusMessage = "Extracting face features..."
                                    val embedding = withContext(Dispatchers.Default) {
                                        faceService.extractEmbedding(result.faceBitmap)
                                    }
                                    if (embedding != null) {
                                        val embJson = faceService.embeddingToJson(embedding)
                                        prefs.saveFaceEmbedding(embJson)

                                        try {
                                            val file = java.io.File(context.filesDir, "enrolled_face.jpg")
                                            val out = java.io.FileOutputStream(file)
                                            result.faceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                            out.flush()
                                            out.close()
                                        } catch (e: Exception) {
                                            android.util.Log.e("Enrollment", "Failed to save face image", e)
                                        }

                                        step = EnrollmentStep.SUCCESS
                                    } else {
                                        errorMessage = "Could not extract face features. Try again."
                                        step = EnrollmentStep.ERROR
                                    }
                                }
                                else -> {
                                    errorMessage = "Face detection failed. Please try again."
                                    step = EnrollmentStep.ERROR
                                }
                            }
                        }
                    }
                )

                EnrollmentStep.PROCESSING -> ProcessingStep(statusMessage)

                EnrollmentStep.SUCCESS -> SuccessStep(onEnrollmentComplete)

                EnrollmentStep.ERROR -> ErrorStep(
                    message = errorMessage,
                    onRetry = { step = EnrollmentStep.CAMERA }
                )
            }
        }
    }
}

@Composable
fun InstructionsStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🪪", style = MaterialTheme.typography.headlineLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
        Spacer(Modifier.height(8.dp))
        Text("Face Enrollment", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            "One-time setup to register your face as your identity reference.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        val tips = listOf(
            "✅ Ensure good lighting",
            "✅ Face the camera directly",
            "✅ Remove sunglasses or heavy accessories",
            "✅ Only your face should be in frame",
            "✅ Keep a neutral expression"
        )
        tips.forEach { tip ->
            Text(tip, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
        ) {
            Text("Start Camera", color = Color.White)
        }
    }
}

@Composable
fun CameraStep(
    imageCapture: ImageCapture,
    cameraExecutor: java.util.concurrent.ExecutorService,
    onCapture: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        Log.e("EnrollmentCamera", "Camera bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Face oval guide overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.42f
            val rx = size.width * 0.34f
            val ry = size.height * 0.28f

            // Dim overlay
            drawRect(color = Color.Black.copy(alpha = 0.55f), size = size)

            // Cut out oval
            drawOval(
                color = Color.Transparent,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                blendMode = BlendMode.Clear
            )

            // Oval border
            drawOval(
                color = PrimaryBlueLight,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        // Instructions at top
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Position your face in the oval",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Capture button at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    imageCapture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = imageProxyToBitmap(image)
                                image.close()
                                bitmap?.let { onCapture(it) }
                            }
                            override fun onError(exception: ImageCaptureException) {
                                Log.e("EnrollmentCamera", "Capture error: ${exception.message}")
                            }
                        }
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.White, CircleShape)
                    .border(4.dp, PrimaryBlue, CircleShape)
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = "Capture",
                    modifier = Modifier.size(36.dp),
                    tint = PrimaryBlue
                )
            }
        }
    }
}

@Composable
fun ProcessingStep(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = PrimaryBlueLight, modifier = Modifier.size(64.dp), strokeWidth = 5.dp)
        Spacer(Modifier.height(24.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
fun SuccessStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(100.dp).background(AccentGreen, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Face Enrolled!", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            "Your face has been registered. You can now mark attendance.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Text("Go to Dashboard", color = Color.White)
        }
    }
}

@Composable
fun ErrorStep(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("❌", style = MaterialTheme.typography.headlineLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
        Spacer(Modifier.height(16.dp))
        Text("Enrollment Failed", style = MaterialTheme.typography.headlineMedium, color = AccentRed)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))
            Text("Try Again", color = Color.White)
        }
    }
}

fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        // Fix rotation for front camera
        val matrix = Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) // mirror
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        Log.e("ImageProxy", "Failed to convert: ${e.message}")
        null
    }
}
