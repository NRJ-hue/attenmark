package com.attendmark.app.ui.screens.student

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.attendmark.app.data.FirestoreRepository
import com.attendmark.app.data.QrValidationResult
import com.attendmark.app.data.QrValidationService
import com.attendmark.app.data.QrScanCoordinator
import com.attendmark.app.data.TokenFormatResult
import java.util.concurrent.atomic.AtomicBoolean
import com.attendmark.app.ui.theme.DarkBackground
import com.attendmark.app.ui.theme.PrimaryBlue
import com.attendmark.app.ui.theme.PrimaryBlueLight
import com.attendmark.app.ui.theme.StatusApproved
import com.attendmark.app.ui.theme.StatusRejected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    courseCode: String,
    courseName: String = "",
    prefetchedSessionId: String? = null,
    onScanSuccess: (sessionId: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val firestoreRepo = remember { FirestoreRepository() }
    val validationService = remember { QrValidationService() }

    var resolvedSessionId by remember { mutableStateOf(prefetchedSessionId ?: "") }
    var isCheckingSession by remember { mutableStateOf(resolvedSessionId.isBlank()) }
    var sessionErrorMessage by remember { mutableStateOf<String?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Resolve active session if not prefetched
    fun fetchActiveSession() {
        isCheckingSession = true
        sessionErrorMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                firestoreRepo.getActiveSessionResult(courseCode)
            }
            if (result.isSuccess) {
                val session = result.getOrNull()
                if (session != null) {
                    val sId = session["sessionId"] as? String ?: ""
                    if (sId.isNotBlank()) {
                        resolvedSessionId = sId
                    } else {
                        sessionErrorMessage = "Active session is missing an authoritative session ID."
                    }
                } else {
                    sessionErrorMessage = "No active attendance session found for $courseCode. Ask your faculty to start a session."
                }
            } else {
                val ex = result.exceptionOrNull()
                sessionErrorMessage = "Unable to connect to session service: ${ex?.localizedMessage ?: "Unknown network error"}. Please check your connection."
            }
            isCheckingSession = false
        }
    }

    LaunchedEffect(courseCode) {
        if (resolvedSessionId.isBlank()) {
            fetchActiveSession()
        }
    }

    // Scanner state
    val coordinator = remember { QrScanCoordinator() }
    val hasNavigated = remember { AtomicBoolean(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var validationErrorMessage by remember { mutableStateOf<String?>(null) }
    var statusInfo by remember { mutableStateOf<String?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var analyzerRef by remember { mutableStateOf<QrCodeAnalyzer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                coordinator.reset()
                analyzerRef?.close()
                cameraExecutor.shutdown()
            } catch (e: Exception) {
                Log.w("QrScannerScreen", "Error during teardown: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Scan Session QR", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (courseName.isNotBlank()) "$courseName ($courseCode)" else courseCode,
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
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
            when {
                // 1. Session resolution in progress
                isCheckingSession -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = PrimaryBlueLight, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Connecting to session...",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                // 2. No active session available
                sessionErrorMessage != null -> {
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
                            tint = Color(0xFFFFB74D),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Session Unavailable",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            sessionErrorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { fetchActiveSession() },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry", color = Color.White)
                        }
                    }
                }

                // 3. Camera permission not granted
                !hasCameraPermission -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Camera Access Required",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "AttendIQ requires camera access to scan the rotating QR code projected in class.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Enable Camera", color = Color.White)
                        }
                    }
                }

                // 4. Active camera preview with QR scanner overlay
                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    try {
                                        val cameraProvider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }

                                        val imageAnalysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()

                                        val analyzer = QrCodeAnalyzer { rawQrString ->
                                            // Coordinate single active scan attempt
                                            if (!coordinator.startScanAttempt(rawQrString)) {
                                                return@QrCodeAnalyzer
                                            }

                                            // Validate token format locally before network call
                                            when (val formatResult = QrValidationService.validateTokenPayload(rawQrString)) {
                                                is TokenFormatResult.Invalid -> {
                                                    coordinator.onValidationFailure(formatResult.reason)
                                                    isProcessing = false
                                                    statusInfo = null
                                                    validationErrorMessage = formatResult.reason
                                                    // analyzerRef remains paused to hold stable error state
                                                    return@QrCodeAnalyzer
                                                }
                                                is TokenFormatResult.Valid -> {
                                                    // Valid 64-char token detected
                                                    isProcessing = true
                                                    validationErrorMessage = null
                                                    statusInfo = "Validating token with AttendIQ server..."

                                                    scope.launch {
                                                        val result = validationService.validateToken(
                                                            tokenId = formatResult.token,
                                                            courseCode = courseCode,
                                                            sessionId = resolvedSessionId
                                                        )

                                                        when (result) {
                                                            is QrValidationResult.Success -> {
                                                                coordinator.onValidationSuccess()
                                                                statusInfo = "Token verified! Starting biometric flow..."
                                                                if (hasNavigated.compareAndSet(false, true)) {
                                                                    analyzerRef?.pauseScanning()
                                                                    onScanSuccess(result.sessionId)
                                                                }
                                                            }
                                                            is QrValidationResult.Expired -> {
                                                                val msg = "QR code expired. Please point camera at the freshly rotated code on the projector."
                                                                coordinator.onValidationFailure(msg)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = msg
                                                            }
                                                            is QrValidationResult.SessionInactive -> {
                                                                val msg = "Attendance session has ended or is no longer active."
                                                                coordinator.onValidationFailure(msg)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = msg
                                                            }
                                                            is QrValidationResult.AlreadyRecorded -> {
                                                                val msg = "Your attendance has already been recorded for this session."
                                                                coordinator.onValidationFailure(msg)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = msg
                                                            }
                                                            is QrValidationResult.RetriesExhausted -> {
                                                                val msg = "Maximum verification attempts reached (2 of 2). Please submit a manual request to your instructor."
                                                                coordinator.onValidationFailure(msg)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = msg
                                                            }
                                                            is QrValidationResult.PermissionDenied -> {
                                                                coordinator.onValidationFailure(result.message)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = result.message
                                                            }
                                                            is QrValidationResult.NotAuthenticated -> {
                                                                val msg = "You must be logged in to validate attendance."
                                                                coordinator.onValidationFailure(msg)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = msg
                                                            }
                                                            is QrValidationResult.NotFound -> {
                                                                val msg = "Invalid QR code. Please scan the current code displayed on the faculty screen."
                                                                coordinator.onValidationFailure(msg)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = msg
                                                            }
                                                            is QrValidationResult.InvalidArgument -> {
                                                                coordinator.onValidationFailure(result.message)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = result.message
                                                            }
                                                            is QrValidationResult.Error -> {
                                                                coordinator.onValidationFailure(result.message)
                                                                isProcessing = false
                                                                statusInfo = null
                                                                validationErrorMessage = result.message
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        analyzerRef = analyzer
                                        imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageAnalysis
                                        )
                                    } catch (e: Exception) {
                                        Log.e("QrScannerScreen", "Camera bind failed", e)
                                        sessionErrorMessage = "Could not initialize camera preview: ${e.message}"
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Reticle Overlay (Scanner viewfinder)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val boxSize = size.width * 0.72f
                            val left = (size.width - boxSize) / 2
                            val top = (size.height - boxSize) / 2.3f

                            // Dim background
                            drawRect(
                                color = Color.Black.copy(alpha = 0.60f),
                                size = size
                            )

                            // Clear viewfinder hole
                            drawRoundRect(
                                color = Color.Transparent,
                                topLeft = Offset(left, top),
                                size = Size(boxSize, boxSize),
                                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                                blendMode = BlendMode.Clear
                            )

                            // Highlight border around reticle
                            val borderColor = if (isProcessing) StatusApproved else PrimaryBlueLight
                            drawRoundRect(
                                color = borderColor,
                                topLeft = Offset(left, top),
                                size = Size(boxSize, boxSize),
                                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }

                        // Guidance header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = 28.dp, start = 20.dp, end = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = PrimaryBlueLight)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "Point camera at the classroom projector QR",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        // Processing Indicator Overlay
                        if (isProcessing) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(32.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        color = StatusApproved,
                                        modifier = Modifier.size(48.dp),
                                        strokeWidth = 4.dp
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = statusInfo ?: "Validating QR token...",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Error Banner at Bottom
                        AnimatedVisibility(
                            visible = validationErrorMessage != null && !isProcessing,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 36.dp, start = 16.dp, end = 16.dp)
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1D1D)),
                                shape = RoundedCornerShape(16.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StatusRejected)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusRejected)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Validation Error",
                                            color = StatusRejected,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = validationErrorMessage ?: "",
                                        color = Color.White.copy(alpha = 0.9f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = {
                                            validationErrorMessage = null
                                            statusInfo = null
                                            isProcessing = false
                                            coordinator.retry()
                                            analyzerRef?.resumeScanning()
                                        }) {
                                            Text("Try Again", color = PrimaryBlueLight)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
