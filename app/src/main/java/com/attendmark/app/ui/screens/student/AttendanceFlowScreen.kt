package com.attendmark.app.ui.screens.student

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.attendmark.app.data.*
import com.attendmark.app.ui.screens.enrollment.imageProxyToBitmap
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

// ─── Flow Steps ───────────────────────────────────────────────────────────────
enum class AttendanceFlowStep {
    LIVENESS_CHALLENGE,
    BEREAL_BACK_CAPTURE,
    BEREAL_FRONT_CAPTURE,
    SUBMITTING,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceFlowScreen(
    courseCode: String = "CS101",
    onResult: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferences(context) }
    val faceService = remember { FaceRecognitionService(context) }
    val livenessService = remember { LivenessDetectionService() }

    val userEmail by prefs.userEmail.collectAsState(initial = "")
    val userName by prefs.userName.collectAsState(initial = "")
    val userUid by prefs.userUid.collectAsState(initial = "")
    val faceEmbeddingJson by prefs.faceEmbedding.collectAsState(initial = "")

    var currentStep by remember { mutableStateOf(AttendanceFlowStep.LIVENESS_CHALLENGE) }
    var challenge by remember { mutableStateOf(livenessService.randomChallenge()) }
    var selfieCapture by remember { mutableStateOf<Bitmap?>(null) }
    var classroomCapture by remember { mutableStateOf<Bitmap?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    val imageCaptureRef = remember { ImageCapture.Builder().build() }
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
                title = { Text("Mark Attendance", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            if (hasCameraPermission) {
                when (currentStep) {
                    AttendanceFlowStep.LIVENESS_CHALLENGE -> {
                        LivenessChallengeStep(
                            challenge = challenge,
                            imageCapture = imageCaptureRef,
                            cameraExecutor = cameraExecutor,
                            livenessService = livenessService,
                            onChallengePassed = {
                                currentStep = AttendanceFlowStep.BEREAL_BACK_CAPTURE
                            }
                        )
                    }

                    AttendanceFlowStep.BEREAL_BACK_CAPTURE -> {
                        ClassroomBackCaptureStep(
                            imageCapture = imageCaptureRef,
                            cameraExecutor = cameraExecutor,
                            onCapture = { classroomBitmap ->
                                classroomCapture = classroomBitmap
                                currentStep = AttendanceFlowStep.BEREAL_FRONT_CAPTURE
                            }
                        )
                    }
                    
                    AttendanceFlowStep.BEREAL_FRONT_CAPTURE -> {
                        ClassroomFrontCaptureStep(
                            classroomBitmap = classroomCapture,
                            imageCapture = imageCaptureRef,
                            cameraExecutor = cameraExecutor,
                            onCapture = { selfieBitmap ->
                                selfieCapture = selfieBitmap
                                currentStep = AttendanceFlowStep.SUBMITTING
                                statusMessage = "Verifying identity..."
                                
                                scope.launch {
                                    val enrolledEmbedding = withContext(Dispatchers.Default) {
                                        if (faceEmbeddingJson.isNotBlank())
                                            faceService.jsonToEmbedding(faceEmbeddingJson)
                                        else null
                                    }

                                    if (enrolledEmbedding == null) {
                                        onResult("error")
                                        return@launch
                                    }

                                    statusMessage = "Comparing face..."
                                    val liveSelfie = selfieCapture
                                    if (liveSelfie == null) {
                                        onResult("error")
                                        return@launch
                                    }

                                    val faceResult = withContext(Dispatchers.Default) {
                                        livenessService.detectFace(liveSelfie)
                                    }

                                    val similarity: Float
                                    val liveEmbedding: FloatArray?

                                    if (faceResult.faceBitmap != null) {
                                        liveEmbedding = withContext(Dispatchers.Default) {
                                            faceService.extractEmbedding(faceResult.faceBitmap)
                                        }
                                        similarity = if (liveEmbedding != null) {
                                            faceService.cosineSimilarity(enrolledEmbedding, liveEmbedding)
                                        } else 0f
                                    } else {
                                        liveEmbedding = null
                                        similarity = 0f
                                    }

                                    val matchResult = faceService.getMatchResult(similarity)
                                    val matchPct = faceService.similarityToPercentage(similarity)

                                    // Evaluate Classroom Back Camera Photo (Mathematical check for hand covering)
                                    val isClassroomValid = classroomCapture?.let { 
                                        com.attendmark.app.data.ImageValidationUtil.isImageValid(it) 
                                    } ?: false

                                    // Calculate 100-point Overall Trust Score
                                    var trustScore = 0
                                    // 1. Face Match (60 Points max)
                                    trustScore += (matchPct * 0.6f).toInt()
                                    // 2. Liveness Check (20 Points)
                                    trustScore += 20 // Passed earlier in the flow
                                    // 3. Photo Validation (20 Points)
                                    if (isClassroomValid) {
                                        trustScore += 20
                                    }

                                    // Status Overrides based on total score
                                    // Students cannot submit directly as APPROVED per Firestore rules;
                                    // High trust scores submit as REVIEW awaiting confirmation
                                    val finalStatus = when {
                                        matchResult == FaceRecognitionService.MatchResult.NO_MATCH || matchResult == FaceRecognitionService.MatchResult.WEAK_MATCH -> AttendanceStatus.REJECTED
                                        trustScore >= 50 -> AttendanceStatus.REVIEW
                                        else -> AttendanceStatus.REJECTED
                                    }

                                    // Compress photos to Base64 for Firestore
                                    val classroomBase64 = classroomCapture?.let { com.attendmark.app.data.ImageValidationUtil.compressToBase64(it) } ?: ""
                                    val selfieBase64 = selfieCapture?.let { com.attendmark.app.data.ImageValidationUtil.compressToBase64(it) } ?: ""

                                    val firestoreRepo = FirestoreRepository()
                                    val activeSession = withContext(Dispatchers.IO) {
                                        firestoreRepo.getActiveSession(courseCode)
                                    }
                                    val currentSessionId = (activeSession?.get("sessionId") as? String)
                                        ?: "session_${courseCode}_${System.currentTimeMillis()}"
                                    val currentUid = if (userUid.isNotBlank()) userUid
                                        else (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")

                                    val docId = if (currentSessionId.isNotBlank() && currentUid.isNotBlank()) {
                                        "${currentSessionId}_${currentUid}"
                                    } else {
                                        java.util.UUID.randomUUID().toString()
                                    }

                                    val record = AttendanceRecord(
                                        id = docId,
                                        sessionId = currentSessionId,
                                        studentUid = currentUid,
                                        studentName = userName,
                                        studentEmail = userEmail,
                                        courseCode = courseCode,
                                        faceMatchScore = similarity,
                                        matchPercentage = matchPct,
                                        matchResult = matchResult.name,
                                        livenessChallenge = challenge.name,
                                        livenessPassed = true,
                                        status = finalStatus,
                                        overallScore = trustScore,
                                        selfieBase64 = selfieBase64,
                                        classroomPhotoBase64 = classroomBase64
                                    )
                                    
                                    firestoreRepo.submitRecord(
                                        record = record,
                                        onSuccess = { onResult(record.id) },
                                        onFailure = { onResult("error") }
                                    )
                                }
                            }
                        )
                    }

                    AttendanceFlowStep.SUBMITTING -> {
                        SubmittingStep(message = statusMessage)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Camera permission is required.", color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}

// ─── Step 1: Liveness Challenge ────────────────────────────────────────────────
@Composable
fun LivenessChallengeStep(
    challenge: LivenessDetectionService.LivenessChallenge,
    imageCapture: ImageCapture,
    cameraExecutor: java.util.concurrent.ExecutorService,
    livenessService: LivenessDetectionService,
    onChallengePassed: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var challengeStatus by remember { mutableStateOf("Waiting...") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var challengePassed by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "scale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
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
                        Log.e("LivenessCamera", "Bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.42f
            val rx = size.width * 0.36f
            val ry = size.height * 0.27f
            drawRect(color = Color.Black.copy(alpha = 0.5f), size = size)
            drawOval(Color.Transparent, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), blendMode = BlendMode.Clear)
            val borderColor = if (challengePassed) Color(0xFF4CAF50) else PrimaryBlueLight
            drawOval(borderColor, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(5.dp.toPx()))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PrimaryBlue.copy(alpha = 0.92f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Step 1: Liveness Check", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text(challenge.emoji, fontSize = 36.sp, modifier = Modifier.scale(scale))
                    Text(challenge.instruction, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(challengeStatus, style = MaterialTheme.typography.bodyMedium, color = if (challengePassed) Color(0xFF81C784) else Color.White.copy(alpha = 0.7f))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!challengePassed) {
                Button(
                    onClick = {
                        if (!isAnalyzing) {
                            isAnalyzing = true
                            challengeStatus = "Analyzing..."
                            imageCapture.takePicture(
                                cameraExecutor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = imageProxyToBitmap(image)
                                        image.close()
                                        if (bitmap != null) {
                                            scope.launch {
                                                val result = withContext(Dispatchers.Default) {
                                                    livenessService.analyzeFrame(bitmap, challenge, isFrontCamera = true)
                                                }
                                                isAnalyzing = false
                                                when {
                                                    result.noFace -> challengeStatus = "No face detected."
                                                    result.multipleFaces -> challengeStatus = "Multiple faces detected."
                                                    result.challengePassed -> {
                                                        challengeStatus = "✅ Passed! Proceeding..."
                                                        challengePassed = true
                                                        delay(1000)
                                                        onChallengePassed()
                                                    }
                                                    else -> challengeStatus = "Action not detected. Try again!"
                                                }
                                            }
                                        } else {
                                            isAnalyzing = false
                                            challengeStatus = "Capture failed. Try again."
                                        }
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        isAnalyzing = false
                                        challengeStatus = "Camera error. Try again."
                                    }
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    enabled = !isAnalyzing
                ) {
                    if (isAnalyzing) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else Text("Check Now", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ─── Step 2: Classroom Back Capture ────────────────────────────────────────────
@Composable
fun ClassroomBackCaptureStep(
    imageCapture: ImageCapture,
    cameraExecutor: java.util.concurrent.ExecutorService,
    onCapture: (Bitmap) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isCapturing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        Log.e("BackCamera", "Bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 16.dp, start = 16.dp, end = 16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Step 2: 📸 BeReal Capture", style = MaterialTheme.typography.labelMedium, color = PrimaryBlueLight)
                    Text("Point at your classroom and click capture", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    if (!isCapturing) {
                        isCapturing = true
                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    image.close()
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    bitmap?.let { onCapture(it) }
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    isCapturing = false
                                }
                            }
                        )
                    }
                },
                modifier = Modifier.size(80.dp).background(Color.White, CircleShape).border(4.dp, PrimaryBlue, CircleShape),
                enabled = !isCapturing
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Capture", modifier = Modifier.size(36.dp), tint = PrimaryBlue)
                }
            }
        }
    }
}

// ─── Step 3: Classroom Front Capture (Auto) ───────────────────────────────────
@Composable
fun ClassroomFrontCaptureStep(
    classroomBitmap: Bitmap?,
    imageCapture: ImageCapture,
    cameraExecutor: java.util.concurrent.ExecutorService,
    onCapture: (Bitmap) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isCapturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1200) // Wait 1.2s for camera to flip and auto-expose
        if (!isCapturing) {
            isCapturing = true
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        image.close()
                        bitmap?.let { onCapture(it) }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                    }
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        Log.e("FrontCamera", "Bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        
        classroomBitmap?.let { bgImg ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp)
                    .size(100.dp, 130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(3.dp, Color.White, RoundedCornerShape(12.dp))
            ) {
                Image(
                    bitmap = bgImg.asImageBitmap(),
                    contentDescription = "Classroom",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 16.dp, start = 16.dp, end = 130.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Capturing face in 1 sec...", style = MaterialTheme.typography.labelMedium, color = PrimaryBlueLight)
                }
            }
        }
    }
}

// ─── Submitting Step ───────────────────────────────────────────────────────────
@Composable
fun SubmittingStep(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}
