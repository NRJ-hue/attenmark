package com.attendmark.app.ui.screens.student

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendmark.app.data.AttendanceRecord
import com.attendmark.app.data.AttendanceStatus
import com.attendmark.app.data.AttendanceStore
import com.attendmark.app.data.UserPreferences
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDashboardScreen(
    onMarkAttendance: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()

    val userName by prefs.userName.collectAsState(initial = "Student")
    val userEmail by prefs.userEmail.collectAsState(initial = "")
    val isFaceEnrolled by prefs.isFaceEnrolled.collectAsState(initial = false)

    val attendanceRecords = remember { mutableStateListOf<AttendanceRecord>() }
    val firestoreRepo = remember { com.attendmark.app.data.FirestoreRepository() }
    val locationService = remember { com.attendmark.app.data.LocationService(context) }
    
    var locationError by remember { mutableStateOf<String?>(null) }
    var isCheckingLocation by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || 
                      permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            isCheckingLocation = true
            locationError = null
            scope.launch {
                val (loc, errorMsg) = locationService.getCurrentLocation()
                if (loc != null) {
                    val session = firestoreRepo.getActiveSession("CS101")
                    if (session != null) {
                        val facLat = session["latitude"] as? Double ?: 0.0
                        val facLon = session["longitude"] as? Double ?: 0.0
                        val distance = com.attendmark.app.data.LocationService.calculateDistance(
                            loc.latitude, loc.longitude, facLat, facLon
                        )
                        if (distance <= 50f) {
                            onMarkAttendance()
                        } else {
                            locationError = "You are ${distance.toInt()} meters away. You must be within 50 meters of the classroom."
                        }
                    } else {
                        locationError = "No active attendance session found. Ask your faculty to start a session."
                    }
                } else {
                    locationError = "Location Error: ${errorMsg ?: "Ensure GPS is ON"}"
                }
                isCheckingLocation = false
            }
        } else {
            locationError = "Location permission is required to verify classroom presence."
        }
    }

    LaunchedEffect(userEmail) {
        if (userEmail.isNotBlank()) {
            firestoreRepo.getRecordsFlow().collect { records ->
                attendanceRecords.clear()
                attendanceRecords.addAll(records.filter { it.studentEmail == userEmail })
            }
        }
    }

    val totalApproved = attendanceRecords.count { it.status == AttendanceStatus.APPROVED }
    val totalReview = attendanceRecords.count { it.status == AttendanceStatus.REVIEW }
    val totalRejected = attendanceRecords.count { it.status == AttendanceStatus.REJECTED }

    var showManualRequestDialog by remember { mutableStateOf(false) }

    if (showManualRequestDialog) {
        var reasonText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualRequestDialog = false },
            title = { Text("Manual Attendance Request") },
            text = {
                Column {
                    Text("If you cannot verify your identity automatically (e.g. camera broken, injury), you can submit a manual request to your professor.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Reason for manual request...") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val record = AttendanceRecord(
                            studentName = userName,
                            studentEmail = userEmail,
                            courseCode = "CS101",
                            faceMatchScore = 0f,
                            matchPercentage = 0,
                            matchResult = "MANUAL_REQUEST",
                            livenessChallenge = "NONE",
                            livenessPassed = false,
                            status = AttendanceStatus.REVIEW,
                            overallScore = 0,
                            rejectionReason = "Manual Request: $reasonText" // Store the student's message here temporarily for the professor to see
                        )
                        firestoreRepo.submitRecord(record, onSuccess = {}, onFailure = {})
                        showManualRequestDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Submit Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualRequestDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AttendMark", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue),
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            prefs.logout()
                            onLogout()
                        }
                    }) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout", tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { showManualRequestDialog = true },
                    containerColor = Color.White,
                    contentColor = PrimaryBlue,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Manual Request")
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    containerColor = PrimaryBlue,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text(if (isCheckingLocation) "Checking Location..." else "Mark Attendance") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceLight)
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Welcome card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(listOf(PrimaryBlue, PrimaryBlueLight)),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Welcome back,", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                                Text(userName, style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isFaceEnrolled) Icons.Default.VerifiedUser else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isFaceEnrolled) Color(0xFF81C784) else Color(0xFFFFD54F),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (isFaceEnrolled) "Face Enrolled" else "Not Enrolled",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Change Photo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PrimaryBlueLight,
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .clickable {
                                            scope.launch {
                                                prefs.clearFaceEnrollment()
                                                val faceFile = java.io.File(context.filesDir, "enrolled_face.jpg")
                                                if (faceFile.exists()) faceFile.delete()
                                            }
                                        }
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                                    .border(2.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val faceFile = java.io.File(context.filesDir, "enrolled_face.jpg")
                                if (faceFile.exists()) {
                                    val bitmap = android.graphics.BitmapFactory.decodeFile(faceFile.absolutePath)
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Enrolled Face",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Text("👨‍🎓", fontSize = 28.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Location Error Banner
            item {
                AnimatedVisibility(visible = locationError != null) {
                    locationError?.let {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = StatusRejected.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOff, contentDescription = null, tint = StatusRejected)
                                Spacer(Modifier.width(12.dp))
                                Text(it, color = StatusRejected, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // Stats row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatChip(
                        label = "Approved",
                        value = totalApproved.toString(),
                        color = StatusApproved,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        label = "Review",
                        value = totalReview.toString(),
                        color = StatusReview,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        label = "Rejected",
                        value = totalRejected.toString(),
                        color = StatusRejected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // History header
            item {
                Text(
                    "Attendance History",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (attendanceRecords.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📋", fontSize = 48.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("No attendance records yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary, textAlign = TextAlign.Center)
                            Text("Tap 'Mark Attendance' to get started", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
                        }
                    }
                }
            } else {
                items(attendanceRecords) { record ->
                    AttendanceHistoryCard(record = record)
                }
            }

            item { Spacer(Modifier.height(80.dp)) } // FAB space
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = color)
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
    }
}

@Composable
fun AttendanceHistoryCard(record: AttendanceRecord) {
    val statusColor = when (record.status) {
        AttendanceStatus.APPROVED -> StatusApproved
        AttendanceStatus.REVIEW -> StatusReview
        AttendanceStatus.REJECTED -> StatusRejected
    }
    
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot
                Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(record.courseCode, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(record.formattedTime(), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Text("Liveness: ${record.livenessChallenge.replace("_", " ")}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${record.overallScore}/100",
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                    Text("Trust Score", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            record.status.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                
                val facePoints = (record.matchPercentage * 0.6f).toInt()
                val livenessPoints = if (record.livenessPassed) 20 else 0
                val backCameraValid = record.overallScore > (facePoints + livenessPoints)
                
                Text("Score Breakdown", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Face Match (${record.matchPercentage}%)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("+$facePoints", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Liveness Check", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(if (record.livenessPassed) "+20" else "+0", style = MaterialTheme.typography.bodySmall, color = if (record.livenessPassed) StatusApproved else StatusRejected)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Back Camera", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(if (backCameraValid) "+20" else "+0", style = MaterialTheme.typography.bodySmall, color = if (backCameraValid) StatusApproved else StatusRejected)
                }
                
                if (record.rejectionReason != null) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = StatusRejected.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Rejection Reason", style = MaterialTheme.typography.labelMedium, color = StatusRejected)
                            Text(record.rejectionReason, style = MaterialTheme.typography.bodySmall, color = StatusRejected)
                        }
                    }
                }
            }
        }
    }
}
