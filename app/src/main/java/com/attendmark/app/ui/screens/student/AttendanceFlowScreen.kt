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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.attendmark.app.data.*
import com.attendmark.app.ui.screens.enrollment.imageProxyToBitmap
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

// ─── Flow Steps (QR Verified -> Liveness -> Selfie -> Proximity/Submit) ───────
enum class AttendanceFlowStep {
    LIVENESS_CHALLENGE,
    SELFIE_CAPTURE,
    SUBMITTING,
    FAILED_ATTEMPT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceFlowScreen(
    courseCode: String = "CS101",
    sessionId: String = "",
    onResult: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferences(context) }
    val faceService = remember { FaceRecognitionService(context) }
    val livenessService = remember { LivenessDetectionService() }
    val qrValidationService = remember { QrValidationService() }

    val userEmail by prefs.userEmail.collectAsState(initial = "")
    val userName by prefs.userName.collectAsState(initial = "")
    val userUid by prefs.userUid.collectAsState(initial = "")
    val faceEmbeddingJson by prefs.faceEmbedding.collectAsState(initial = "")

    var currentStep by remember { mutableStateOf(AttendanceFlowStep.LIVENESS_CHALLENGE) }
    var challenge by remember { mutableStateOf(livenessService.randomChallenge()) }
    var selfieCapture by remember { mutableStateOf<Bitmap?>(null) }
    var statusMessage by remember { mutableStateOf("") }

    var failureTitle by remember { mutableStateOf("Verification Problem") }
    var failureDetails by remember { mutableStateOf("") }
    var isRetryEligible by remember { mutableStateOf(false) }

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
                title = { Text("Biometric Attendance", color = Color.White) },
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
            if (sessionId.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusRejected,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "QR Token Required",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A validated session QR code is required before proceeding to biometric verification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Return to Scanner", color = Color.White)
                    }
                }
            } else if (hasCameraPermission) {
                when (currentStep) {
                    // Step 1: Front camera liveness challenge
                    AttendanceFlowStep.LIVENESS_CHALLENGE -> {
                        LivenessChallengeStep(
                            challenge = challenge,
                            imageCapture = imageCaptureRef,
                            cameraExecutor = cameraExecutor,
                            livenessService = livenessService,
                            onChallengePassed = {
                                currentStep = AttendanceFlowStep.SELFIE_CAPTURE
                            }
                        )
                    }

                    // Step 2: Front camera verification selfie
                    AttendanceFlowStep.SELFIE_CAPTURE -> {
                        SelfieCaptureStep(
                            imageCapture = imageCaptureRef,
                            cameraExecutor = cameraExecutor,
                            onCapture = { selfieBitmap ->
                                selfieCapture = selfieBitmap
                                currentStep = AttendanceFlowStep.SUBMITTING
                                statusMessage = "Verifying biometric features..."

                                scope.launch {
                                    val enrolledEmbedding = withContext(Dispatchers.Default) {
                                        if (faceEmbeddingJson.isNotBlank())
                                            faceService.jsonToEmbedding(faceEmbeddingJson)
                                        else null
                                    }

                                    if (enrolledEmbedding == null) {
                                        failureTitle = "Enrollment Missing"
                                        failureDetails = "Your face profile is not enrolled on this device. Please re-enroll in settings."
                                        isRetryEligible = false
                                        currentStep = AttendanceFlowStep.FAILED_ATTEMPT
                                        return@launch
                                    }

                                    statusMessage = "Matching face features..."
                                    val liveSelfie = selfieCapture
                                    if (liveSelfie == null) {
                                        failureTitle = "Capture Failed"
                                        failureDetails = "Could not process selfie image."
                                        isRetryEligible = true
                                        currentStep = AttendanceFlowStep.FAILED_ATTEMPT
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

                                    // GPS Proximity Verification
                                    statusMessage = "Verifying classroom GPS..."
                                    val locationService = LocationService(context)
                                    val (loc, locErr) = locationService.getCurrentLocation()
                                    val firestoreRepo = FirestoreRepository()
                                    val activeSession = withContext(Dispatchers.IO) {
                                        firestoreRepo.getSessionById(sessionId) ?: firestoreRepo.getActiveSession(courseCode)
                                    }
                                    val facLat = (activeSession?.get("latitude") as? Number)?.toDouble() ?: 0.0
                                    val facLon = (activeSession?.get("longitude") as? Number)?.toDouble() ?: 0.0

                                    val gpsEval = LocationService.evaluateProximity(
                                        location = loc,
                                        locationError = locErr,
                                        classroomLat = facLat,
                                        classroomLon = facLon
                                    )

                                    // Calculate 100-point Overall Trust Score:
                                    // 1. QR Session Presence: 20 points
                                    // 2. Liveness Challenge Passed: 20 points
                                    // 3. Face Match: up to 60 points
                                    var trustScore = 20 + 20 + (matchPct * 0.6f).toInt()

                                    // Adjust for GPS accuracy uncertainty
                                    if (gpsEval.status == GpsVerificationStatus.UNCERTAIN_ACCURACY) {
                                        trustScore = (trustScore - 15).coerceAtLeast(0)
                                    }

                                    // Check for hard rejection conditions
                                    val isFaceMatchFailed = (matchResult == FaceRecognitionService.MatchResult.NO_MATCH || 
                                                             matchResult == FaceRecognitionService.MatchResult.WEAK_MATCH)
                                    val isGpsOutside = (gpsEval.status == GpsVerificationStatus.OUTSIDE_GEOFENCE)

                                    val finalStatus: AttendanceStatus
                                    val finalRejectionReason: String?

                                    when {
                                        isGpsOutside -> {
                                            finalStatus = AttendanceStatus.REJECTED
                                            finalRejectionReason = gpsEval.errorMessage ?: "GPS location outside classroom geofence."
                                            
                                            // Record GPS violation on backend claim
                                            withContext(Dispatchers.IO) {
                                                qrValidationService.recordAttemptFailure(
                                                    sessionId = sessionId,
                                                    courseCode = courseCode,
                                                    failureReason = "GPS_OUTSIDE_GEOFENCE"
                                                )
                                            }

                                            failureTitle = "Outside Classroom Geofence"
                                            failureDetails = "Your GPS location is outside the permitted classroom area. Location violations cannot be retried."
                                            isRetryEligible = false
                                        }

                                        isFaceMatchFailed -> {
                                            finalStatus = AttendanceStatus.REJECTED
                                            finalRejectionReason = "Face verification failed: confidence score ($matchPct%) below threshold."

                                            // Record capture failure on backend claim
                                            val failResult = withContext(Dispatchers.IO) {
                                                qrValidationService.recordAttemptFailure(
                                                    sessionId = sessionId,
                                                    courseCode = courseCode,
                                                    failureReason = "FACE_MISMATCH"
                                                )
                                            }

                                            val eligible = failResult.getOrNull()?.get("retryEligible") as? Boolean ?: false
                                            failureTitle = "Face Recognition Failed"
                                            failureDetails = if (eligible) {
                                                "Confidence score was insufficient ($matchPct%). You have 1 retry remaining. Tap 'Retry' to scan the current QR code on the projector and try again."
                                            } else {
                                                "Confidence score insufficient ($matchPct%). Maximum verification attempts reached for this session. Please submit a manual request."
                                            }
                                            isRetryEligible = eligible
                                        }

                                        trustScore >= 50 -> {
                                            finalStatus = AttendanceStatus.REVIEW
                                            finalRejectionReason = if (gpsEval.status == GpsVerificationStatus.UNCERTAIN_ACCURACY)
                                                gpsEval.errorMessage else null
                                        }

                                        else -> {
                                            finalStatus = AttendanceStatus.REJECTED
                                            finalRejectionReason = "Overall trust score insufficient ($trustScore/100)."
                                        }
                                    }

                                    // If this was an eligible failure or non-retryable rejection, show failure UI
                                    if (isFaceMatchFailed || isGpsOutside) {
                                        currentStep = AttendanceFlowStep.FAILED_ATTEMPT
                                        // Still persist the rejection record for faculty audit trail
                                        val currentUid = if (userUid.isNotBlank()) userUid
                                            else (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")
                                        val docId = "${sessionId}_${currentUid}"
                                        val selfieBase64 = selfieCapture?.let { com.attendmark.app.data.ImageValidationUtil.compressToBase64(it) } ?: ""

                                        val record = AttendanceRecord(
                                            id = docId,
                                            sessionId = sessionId,
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
                                            rejectionReason = finalRejectionReason
                                        )
                                        withContext(Dispatchers.IO) {
                                            firestoreRepo.submitRecord(record, {}, {})
                                        }
                                        return@launch
                                    }

                                    // Persist valid record to Firestore
                                    val currentUid = if (userUid.isNotBlank()) userUid
                                        else (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")
                                    val docId = "${sessionId}_${currentUid}"
                                    val selfieBase64 = selfieCapture?.let { com.attendmark.app.data.ImageValidationUtil.compressToBase64(it) } ?: ""

                                    val record = AttendanceRecord(
                                        id = docId,
                                        sessionId = sessionId,
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
                                        rejectionReason = finalRejectionReason
                                    )

                                    statusMessage = "Persisting attendance record..."
                                    firestoreRepo.submitRecord(
                                        record = record,
                                        onSuccess = { onResult(record.id) },
                                        onFailure = { err ->
                                            Log.e("AttendanceFlowScreen", "Error submitting attendance record", err)
                                            onResult("error")
                                        }
                                    )
                                }
                            }
                        )
                    }

                    AttendanceFlowStep.SUBMITTING -> {
                        SubmittingStep(message = statusMessage)
                    }

                    AttendanceFlowStep.FAILED_ATTEMPT -> {
                        FailedAttemptStep(
                            title = failureTitle,
                            details = failureDetails,
                            isRetryEligible = isRetryEligible,
                            onRetry = onBack,
                            onDashboard = onBack
                        )
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var detectedText by remember { mutableStateOf("Position your face in the circle") }
    var challengePassed by remember { mutableStateOf(false) }

    val challengeInstruction = when (challenge) {
        LivenessDetectionService.LivenessChallenge.BLINK -> "👀 Blink both eyes"
        LivenessDetectionService.LivenessChallenge.TURN_LEFT -> "👈 Turn your head left"
        LivenessDetectionService.LivenessChallenge.TURN_RIGHT -> "👉 Turn your head right"
        LivenessDetectionService.LivenessChallenge.SMILE -> "😊 Smile at the camera"
    }

    LaunchedEffect(Unit) {
        while (!challengePassed) {
            delay(600)
            if (!isChecking && !challengePassed) {
                isChecking = true
                imageCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()
                            if (bitmap != null) {
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.Default) {
                                            livenessService.analyzeFrame(bitmap, challenge, isFrontCamera = true)
                                        }
                                        if (result.challengePassed) {
                                            challengePassed = true
                                            detectedText = "Challenge Passed! ✅"
                                            delay(800)
                                            onChallengePassed()
                                        } else {
                                            detectedText = when {
                                                result.noFace -> "No face detected"
                                                result.multipleFaces -> "Multiple faces — only one person"
                                                else -> "Perform: $challengeInstruction"
                                            }
                                        }
                                    } finally {
                                        isChecking = false
                                    }
                                }
                            } else {
                                isChecking = false
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isChecking = false
                        }
                    }
                )
            }
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
                        Log.e("LivenessCamera", "Bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.40f
            val r = size.width * 0.35f

            drawRect(color = Color.Black.copy(alpha = 0.55f), size = size)

            drawCircle(
                color = Color.Transparent,
                radius = r,
                center = Offset(cx, cy),
                blendMode = BlendMode.Clear
            )

            drawCircle(
                color = if (challengePassed) AccentGreen else PrimaryBlueLight,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 20.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Step 1: Liveness Challenge",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryBlueLight
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = challengeInstruction,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (challengePassed) AccentGreen.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = detectedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ─── Step 2: Verification Selfie Capture ──────────────────────────────────────
@Composable
fun SelfieCaptureStep(
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
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        Log.e("SelfieCamera", "Bind failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Oval face guide
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height * 0.40f
            val rx = size.width * 0.35f
            val ry = size.height * 0.28f

            drawRect(color = Color.Black.copy(alpha = 0.55f), size = size)

            drawOval(
                color = Color.Transparent,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                blendMode = BlendMode.Clear
            )

            drawOval(
                color = PrimaryBlueLight,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 20.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Step 2: Verification Photo", style = MaterialTheme.typography.labelMedium, color = PrimaryBlueLight)
                    Spacer(Modifier.height(4.dp))
                    Text("Align your face and tap capture", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
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
                },
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.White, CircleShape)
                    .border(4.dp, PrimaryBlue, CircleShape),
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

// ─── Step 3: Submitting Progress ──────────────────────────────────────────────
@Composable
fun SubmittingStep(message: String) {
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

// ─── Step 4: Failed Attempt / Retry Dialogue ──────────────────────────────────
@Composable
fun FailedAttemptStep(
    title: String,
    details: String,
    isRetryEligible: Boolean,
    onRetry: () -> Unit,
    onDashboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = StatusRejected,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = details,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(32.dp))

        if (isRetryEligible) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Retry Attendance Verification", color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDashboard,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Cancel", color = Color.White)
            }
        } else {
            Button(
                onClick = onDashboard,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Return to Dashboard", color = Color.White)
            }
        }
    }
}
