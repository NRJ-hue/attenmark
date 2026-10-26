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
    courseCode: String = "CS101",
    courseName: String = "Course",
    onMarkAttendance: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()

    val userName by prefs.userName.collectAsState(initial = "Student")
    val userEmail by prefs.userEmail.collectAsState(initial = "")
    val userUid by prefs.userUid.collectAsState(initial = "")
    val isFaceEnrolled by prefs.isFaceEnrolled.collectAsState(initial = false)

    val attendanceRecords = remember { mutableStateListOf<AttendanceRecord>() }
    val firestoreRepo = remember { com.attendmark.app.data.FirestoreRepository() }
    val locationService = remember { com.attendmark.app.data.LocationService(context) }
    
    var activeSession by remember { mutableStateOf<Map<String, Any>?>(null) }
    var isPrefetchingSession by remember { mutableStateOf(true) }

    LaunchedEffect(courseCode) {
        isPrefetchingSession = true
        activeSession = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            firestoreRepo.getActiveSession(courseCode)
        }
        isPrefetchingSession = false
    }

    LaunchedEffect(userEmail, userUid, courseCode) {
        if (userEmail.isNotBlank()) {
            val effectiveUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: userUid
            firestoreRepo.getStudentRecordsFlow(courseCode, effectiveUid).collect { records ->
                attendanceRecords.clear()
                attendanceRecords.addAll(records)
            }
        }
    }

    val totalApproved = attendanceRecords.count { it.status == AttendanceStatus.APPROVED }
    val totalReview = attendanceRecords.count { it.status == AttendanceStatus.REVIEW }
    val totalRejected = attendanceRecords.count { it.status == AttendanceStatus.REJECTED }

    var showManualRequestDialog by remember { mutableStateOf(false) }

    if (showManualRequestDialog) {
        var reasonText by remember { mutableStateOf("") }
        var manualRequestError by remember { mutableStateOf<String?>(null) }
        var isSubmitting by remember { mutableStateOf(false) }

        val hasActiveSession = activeSession != null &&
            System.currentTimeMillis() < ((activeSession?.get("expiresAt") as? Number)?.toLong() ?: 0L)

        AlertDialog(
            onDismissRequest = {
                if (!isSubmitting) {
                    showManualRequestDialog = false
                    manualRequestError = null
                }
            },
            title = { Text("Manual Attendance Request") },
            text = {
                Column {
                    if (!hasActiveSession) {
                        Text(
                            "Cannot submit manual request: There is no active attendance session for this course ($courseCode). Manual requests can only be submitted while your professor has an active attendance session.",
                            color = StatusRejected,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "If you cannot verify your identity automatically (e.g. camera issue, injury), submit a manual request to your professor with a clear explanation.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = reasonText,
                            onValueChange = {
                                reasonText = it
                                manualRequestError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Reason for manual attendance request...") },
                            enabled = !isSubmitting,
                            singleLine = false,
                            maxLines = 3
                        )
                    }

                    manualRequestError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, color = StatusRejected, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (hasActiveSession) {
                    Button(
                        onClick = {
                            val trimmedReason = reasonText.trim()
                            if (trimmedReason.isBlank()) {
                                manualRequestError = "Please enter a reason for your request."
                                return@Button
                            }
                            isSubmitting = true
                            manualRequestError = null

                            scope.launch {
                                // Authoritatively fetch the active session to verify it is still active
                                val freshSession = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    firestoreRepo.getActiveSession(courseCode)
                                }
                                val currentSessionId = freshSession?.get("sessionId") as? String

                                if (currentSessionId.isNullOrBlank()) {
                                    isSubmitting = false
                                    manualRequestError = "The attendance session for $courseCode is no longer active. Request cannot be submitted."
                                    return@launch
                                }

                                val currentUid = if (userUid.isNotBlank()) userUid
                                    else (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")

                                if (currentUid.isBlank()) {
                                    isSubmitting = false
                                    manualRequestError = "User authentication error. Please sign in again."
                                    return@launch
                                }

                                val docId = "${currentSessionId}_${currentUid}"

                                val record = AttendanceRecord(
                                    id = docId,
                                    sessionId = currentSessionId,
                                    studentUid = currentUid,
                                    studentName = userName,
                                    studentEmail = userEmail,
                                    courseCode = courseCode,
                                    faceMatchScore = 0f,
                                    matchPercentage = 0,
                                    matchResult = "MANUAL_REQUEST",
                                    livenessChallenge = "NONE",
                                    livenessPassed = false,
                                    status = AttendanceStatus.REVIEW,
                                    overallScore = 0,
                                    rejectionReason = "Manual Request: $trimmedReason"
                                )

                                firestoreRepo.submitManualRequest(
                                    record = record,
                                    onSuccess = {
                                        isSubmitting = false
                                        showManualRequestDialog = false
                                    },
                                    onFailure = { ex ->
                                        isSubmitting = false
                                        manualRequestError = ex.message ?: "Failed to submit manual request."
                                    }
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        enabled = !isSubmitting
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Submit Request")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showManualRequestDialog = false },
                    enabled = !isSubmitting
                ) {
                    Text(if (hasActiveSession) "Cancel" else "Close")
                }
            }
        )
    }

    var showLeaveCourseDialog by remember { mutableStateOf(false) }
    var isLeavingCourse by remember { mutableStateOf(false) }
    var leaveCourseError by remember { mutableStateOf<String?>(null) }

    if (showLeaveCourseDialog) {
        AlertDialog(
            onDismissRequest = { if (!isLeavingCourse) showLeaveCourseDialog = false },
            title = { Text("Leave Course") },
            text = {
                Column {
                    Text(
                        "Are you sure you want to unenroll from $courseName ($courseCode)?\n\nYour attendance records and submission history will remain safely preserved for your professor's gradebook.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    leaveCourseError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, color = StatusRejected, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isLeavingCourse = true
                        leaveCourseError = null
                        scope.launch {
                            val result = firestoreRepo.leaveCourse(courseCode)
                            isLeavingCourse = false
                            if (result.isSuccess) {
                                showLeaveCourseDialog = false
                                onBack() // Refresh by returning to course selection
                            } else {
                                leaveCourseError = result.exceptionOrNull()?.message ?: "Failed to leave course."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRejected),
                    enabled = !isLeavingCourse
                ) {
                    if (isLeavingCourse) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Leave Course")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveCourseDialog = false },
                    enabled = !isLeavingCourse
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(courseName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("Student View", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue),
                actions = {
                    IconButton(
                        onClick = { showLeaveCourseDialog = true },
                        enabled = !isLeavingCourse
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Leave Course", tint = Color.White)
                    }
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
                    onClick = { onMarkAttendance() },
                    containerColor = PrimaryBlue,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    text = { Text("Mark Attendance") }
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

            // Active Session Status Banner
            item {
                if (activeSession != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StatusApproved.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusApproved.copy(alpha = 0.5f))
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = StatusApproved)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Session In Progress", color = StatusApproved, style = MaterialTheme.typography.titleSmall)
                                Text("Tap 'Mark Attendance' to scan the rotating classroom QR code.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (!isPrefetchingSession) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("No Active Session", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                                Text("Wait for your instructor to launch the session on the projector.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
            // Row 1: Course code + Status Dot on the left, Trust Score on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = record.courseCode,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${record.overallScore}/100",
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Trust Score",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: Status badge pill - full card width available so long labels wrap gracefully without squeezing columns
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = statusColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = record.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Row 3: Timestamp on the left, Liveness challenge on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.formattedTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = "Liveness: ${record.livenessChallenge.replace("_", " ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
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
                
                when (record.status) {
                    AttendanceStatus.APPROVED -> {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = StatusApproved.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Attendance Approved", style = MaterialTheme.typography.labelMedium, color = StatusApproved)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Your verification scan achieved a trust score of ${record.overallScore}/100, which satisfies the auto-approval threshold (>75) and all mandatory verification gates.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                    AttendanceStatus.REVIEW -> {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = StatusReview.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Awaiting Instructor Approval", style = MaterialTheme.typography.labelMedium, color = StatusReview)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Your verification scan succeeded with a trust score of ${record.overallScore}/100. Scores between 40 and 75 remain Under Review until confirmed and approved by your course instructor.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                    AttendanceStatus.REJECTED -> {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = StatusRejected.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Submission Rejected", style = MaterialTheme.typography.labelMedium, color = StatusRejected)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    record.rejectionReason ?: "Your attendance submission did not meet the minimum verification threshold (<40) or failed a required verification gate.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusRejected
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
