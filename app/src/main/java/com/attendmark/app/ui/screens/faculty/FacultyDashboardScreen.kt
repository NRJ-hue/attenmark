package com.attendmark.app.ui.screens.faculty

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendmark.app.data.AttendanceRecord
import com.attendmark.app.data.AttendanceStatus
import com.attendmark.app.data.AttendanceStore
import com.attendmark.app.data.FaceRecognitionService
import com.attendmark.app.data.UserPreferences
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacultyDashboardScreen(
    courseCode: String = "CS101",
    courseName: String = "Course",
    onLogout: () -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()

    val userName by prefs.userName.collectAsState(initial = "Faculty")
    val userEmail by prefs.userEmail.collectAsState(initial = "")

    val firestoreRepo = remember { com.attendmark.app.data.FirestoreRepository() }
    val locationService = remember { com.attendmark.app.data.LocationService(context) }
    
    // All submissions from all students
    val allRecords = remember { mutableStateListOf<AttendanceRecord>() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Review", "Approved", "Rejected")

    var isSessionActive by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var sessionDuration by remember { mutableIntStateOf(5) }
    var sessionExpiresAt by remember { mutableLongStateOf(0L) }
    var timeRemainingSec by remember { mutableIntStateOf(0) }

    // Countdown timer effect
    LaunchedEffect(isSessionActive, sessionExpiresAt) {
        if (isSessionActive && sessionExpiresAt > 0) {
            while (true) {
                val diff = sessionExpiresAt - System.currentTimeMillis()
                if (diff <= 0) {
                    isSessionActive = false
                    firestoreRepo.stopSession(courseCode)
                    break
                }
                timeRemainingSec = (diff / 1000).toInt()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || 
                      permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            scope.launch {
                val (loc, errorMsg) = locationService.getCurrentLocation()
                if (loc != null) {
                    firestoreRepo.startSession(courseCode, loc.latitude, loc.longitude, userEmail, sessionDuration) { expiresAt ->
                        sessionExpiresAt = expiresAt
                        isSessionActive = true
                    }
                } else {
                    locationError = "Location Error: ${errorMsg ?: "Ensure GPS is on."}"
                }
            }
        } else {
            locationError = "Location permission denied."
        }
    }

    // Load records from Firestore in real-time, filtered to this course
    LaunchedEffect(courseCode) {
        firestoreRepo.getRecordsFlow(courseCode).collect { records ->
            allRecords.clear()
            allRecords.addAll(records)
        }
    }

    val filteredRecords = remember(selectedTab, allRecords.toList()) {
        when (selectedTab) {
            0 -> allRecords.toList()
            1 -> allRecords.filter { it.status == AttendanceStatus.REVIEW }
            2 -> allRecords.filter { it.status == AttendanceStatus.APPROVED }
            3 -> allRecords.filter { it.status == AttendanceStatus.REJECTED }
            else -> allRecords.toList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(courseName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("Faculty Portal", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
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
            // Welcome banner
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(listOf(PrimaryBlueDark, PrimaryBlue)),
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
                            Text("Welcome,", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                            Text(userName, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        }
                        Text("👨‍🏫", fontSize = 36.sp)
                    }
                }
            }

            // Geofence Session Control
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Geofencing Session",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        locationError?.let {
                            Text(it, color = StatusRejected, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (isSessionActive) {
                            Text(
                                "✅ Session is Active.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = StatusApproved,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            val mins = timeRemainingSec / 60
                            val secs = timeRemainingSec % 60
                            val timeString = String.format("%02d:%02d", mins, secs)
                            
                            Text(
                                "Time remaining: $timeString",
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { 
                                    firestoreRepo.stopSession(courseCode)
                                    isSessionActive = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRejected),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("End Session Early")
                            }
                        } else {
                            Text(
                                "Start a session to broadcast your location to students.",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                "Session Duration:",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(5, 10, 15).forEach { duration ->
                                    val isSelected = sessionDuration == duration
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { sessionDuration = duration },
                                        label = { Text("$duration min") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PrimaryBlue,
                                            selectedLabelColor = Color.White,
                                            labelColor = Color.White.copy(alpha = 0.7f)
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Button(
                                onClick = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Start Location Session")
                            }
                        }
                    }
                }
            }

            // Summary stats
            item {
                val approved = allRecords.count { it.status == AttendanceStatus.APPROVED }
                val review = allRecords.count { it.status == AttendanceStatus.REVIEW }
                val rejected = allRecords.count { it.status == AttendanceStatus.REJECTED }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryStatCard("Total", allRecords.size.toString(), TextPrimary, Modifier.weight(1f))
                    SummaryStatCard("✅ Pass", approved.toString(), StatusApproved, Modifier.weight(1f))
                    SummaryStatCard("⚠️ Review", review.toString(), StatusReview, Modifier.weight(1f))
                    SummaryStatCard("❌ Fail", rejected.toString(), StatusRejected, Modifier.weight(1f))
                }
            }

            // Average score if there are submissions
            if (allRecords.isNotEmpty()) {
                item {
                    val avgScore = allRecords.map { it.faceMatchScore }.average().toFloat()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Avg Face Match Score", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                Text(
                                    "${(avgScore * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = PrimaryBlue
                                )
                            }
                            Icon(Icons.Default.BarChart, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }

            // Tab filter
            item {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.White,
                    contentColor = PrimaryBlue,
                    edgePadding = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }

            // Record cards
            if (filteredRecords.isEmpty()) {
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
                            Spacer(Modifier.height(8.dp))
                            Text("No submissions yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary, textAlign = TextAlign.Center)
                            Text("Student submissions will appear here", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
                        }
                    }
                }
            } else {
                items(filteredRecords) { record ->
                    FacultySubmissionCard(
                        record = record,
                        onApprove = {
                            firestoreRepo.updateRecordStatus(record.id, com.attendmark.app.data.AttendanceStatus.APPROVED)
                        },
                        onReject = { reason ->
                            firestoreRepo.updateRecordStatus(record.id, com.attendmark.app.data.AttendanceStatus.REJECTED, reason)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryStatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = color)
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun FacultySubmissionCard(
    record: AttendanceRecord,
    onApprove: () -> Unit,
    onReject: (String?) -> Unit
) {
    val isManualRequest = record.matchResult == "MANUAL_REQUEST"
    val statusColor = when (record.status) {
        AttendanceStatus.APPROVED -> StatusApproved
        AttendanceStatus.REVIEW -> StatusReview
        AttendanceStatus.REJECTED -> StatusRejected
    }

    var expanded by remember { mutableStateOf(record.status == AttendanceStatus.REVIEW) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }

    // Reject dialog is hoisted OUTSIDE the Card so it fires correctly
    if (showRejectDialog) {
        AlertDialog(
            onDismissRequest = { showRejectDialog = false },
            title = { Text("Reject Attendance") },
            text = {
                Column {
                    Text("Provide an optional reason for rejection:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Camera covered") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRejectDialog = false
                    onReject(rejectReason.ifBlank { null })
                }) {
                    Text("Reject", color = StatusRejected)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRejectDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Clickable header row (expand/collapse) â€” does NOT swallow button clicks
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isManualRequest) StatusReview.copy(alpha = 0.15f)
                                else statusColor.copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isManualRequest) "✏️" else "👤", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(record.studentName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        if (isManualRequest) {
                            Text("Manual Request", style = MaterialTheme.typography.bodySmall, color = StatusReview)
                        } else {
                            Text(record.studentEmail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        record.status.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isManualRequest) {
                // â”€â”€ Manual Request: show student's reason, no face score â”€â”€â”€â”€â”€â”€
                val studentReason = record.rejectionReason
                    ?.removePrefix("Manual Request: ")
                    ?: "No reason provided"
                Card(
                    colors = CardDefaults.cardColors(containerColor = StatusReview.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("📩 Student's Reason:", style = MaterialTheme.typography.labelMedium, color = StatusReview)
                        Spacer(Modifier.height(4.dp))
                        Text(studentReason, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(record.formattedTime(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)

                if (record.status == AttendanceStatus.REVIEW) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(12.dp))
                    Text("Faculty Review Action", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { showRejectDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRejected)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reject")
                        }
                        Button(
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusApproved)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Approve", color = Color.White)
                        }
                    }
                }

            } else {
                // â”€â”€ Normal Biometric submission â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                val animatedTrustScore by animateFloatAsState(
                    targetValue = record.overallScore / 100f,
                    animationSpec = tween(800),
                    label = "score_anim"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Overall Trust Score", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text("${record.overallScore}/100", style = MaterialTheme.typography.titleMedium, color = statusColor)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { animatedTrustScore },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = statusColor,
                    trackColor = SurfaceLight
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🎭 ${record.livenessChallenge.replace("_", " ")}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(record.formattedTime(), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }

                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(12.dp))

                    val facePoints = (record.matchPercentage * 0.6f).toInt()
                    val livenessPoints = if (record.livenessPassed) 20 else 0
                    val backCameraValid = record.overallScore > (facePoints + livenessPoints)

                    Column(modifier = Modifier.fillMaxWidth().background(SurfaceLight, RoundedCornerShape(8.dp)).padding(12.dp)) {
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
                            Text("Back Camera Image", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text(if (backCameraValid) "+20" else "+0", style = MaterialTheme.typography.bodySmall, color = if (backCameraValid) StatusApproved else StatusRejected)
                        }
                        if (record.rejectionReason != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Rejection Reason:", style = MaterialTheme.typography.labelMedium, color = StatusRejected)
                            Text(record.rejectionReason, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                    }

                    if (record.status == AttendanceStatus.REVIEW) {
                        Spacer(Modifier.height(12.dp))
                        Text("Faculty Review Action", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { showRejectDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRejected)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reject")
                            }
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = StatusApproved)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Approve", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

