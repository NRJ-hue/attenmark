package com.attendmark.app.ui.screens.student

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.attendmark.app.data.AttendanceRecord
import com.attendmark.app.data.AttendanceStatus
import com.attendmark.app.data.AttendanceStore
import com.attendmark.app.data.FaceRecognitionService
import com.attendmark.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceResultScreen(
    recordId: String,
    onBack: () -> Unit
) {
    var record by remember { mutableStateOf<com.attendmark.app.data.AttendanceRecord?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(recordId) {
        val repo = com.attendmark.app.data.FirestoreRepository()
        record = repo.getRecordById(recordId)
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PrimaryBlue)
        }
        return
    }

    val currentRecord = record
    if (currentRecord == null) {
        // Fallback error
        Column(
            modifier = Modifier.fillMaxSize().background(DarkBackground),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Record not found", color = Color.White)
            Button(onClick = onBack) { Text("Go Back") }
        }
        return
    }

    val statusColor = when (currentRecord.status) {
        AttendanceStatus.APPROVED -> StatusApproved
        AttendanceStatus.REVIEW -> StatusReview
        AttendanceStatus.REJECTED -> StatusRejected
    }

    val statusEmoji = when (currentRecord.status) {
        AttendanceStatus.APPROVED -> "✅"
        AttendanceStatus.REVIEW -> "🟡"
        AttendanceStatus.REJECTED -> "❌"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Result", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Big status indicator
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .background(statusColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(statusEmoji, style = MaterialTheme.typography.headlineLarge)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                currentRecord.status.label,
                style = MaterialTheme.typography.headlineMedium,
                color = statusColor
            )

            Text(
                currentRecord.status.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Overall Trust Score Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Overall Trust Score", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            val statusLabel = when (currentRecord.status) {
                                AttendanceStatus.APPROVED -> "High Confidence"
                                AttendanceStatus.REVIEW -> "Moderate Confidence"
                                AttendanceStatus.REJECTED -> "Low Confidence"
                            }
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor
                            )
                        }
                        // Big percentage circle
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(statusColor.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${currentRecord.overallScore}/100",
                                style = MaterialTheme.typography.titleLarge,
                                color = statusColor
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Animated progress bar
                    val animatedProgress by animateFloatAsState(
                        targetValue = currentRecord.overallScore / 100f,
                        animationSpec = tween(1000, easing = EaseOut),
                        label = "trust_score_anim"
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = statusColor,
                        trackColor = DarkBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
                        Text("100", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Verification Details", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    HorizontalDivider(color = DividerColor.copy(alpha = 0.3f))

                    val facePoints = (currentRecord.matchPercentage * 0.6f).toInt()
                    val livenessPoints = if (currentRecord.livenessPassed) 20 else 0
                    val backCameraValid = currentRecord.overallScore > (facePoints + livenessPoints)

                    DetailRow("Student", currentRecord.studentName)
                    DetailRow("Course", currentRecord.courseCode)
                    DetailRow("Time", currentRecord.formattedTime())
                    DetailRow("Overall Score", "${currentRecord.overallScore} / 100")
                    DetailRow("Face Match", "${currentRecord.matchPercentage}%")
                    DetailRow("Liveness Result", if (currentRecord.livenessPassed) "✅ Passed (+20)" else "❌ Failed (+0)")
                    DetailRow("Back Camera", if (backCameraValid) "✅ Valid (+20)" else "❌ Covered (+0)")
                    DetailRow("Receipt ID", currentRecord.id.take(8).uppercase())
                }
            }

            Spacer(Modifier.height(12.dp))

            // Explanation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (currentRecord.status) {
                        AttendanceStatus.APPROVED -> StatusApproved.copy(alpha = 0.1f)
                        AttendanceStatus.REVIEW -> StatusReview.copy(alpha = 0.1f)
                        AttendanceStatus.REJECTED -> StatusRejected.copy(alpha = 0.1f)
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (currentRecord.status) {
                            AttendanceStatus.APPROVED -> Icons.Default.CheckCircle
                            AttendanceStatus.REVIEW -> Icons.Default.Warning
                            AttendanceStatus.REJECTED -> Icons.Default.Cancel
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (currentRecord.status) {
                            AttendanceStatus.APPROVED -> "Your identity has been confirmed with high confidence. Attendance recorded successfully."
                            AttendanceStatus.REVIEW -> "Low confidence match. Your attendance is pending faculty review. Show this receipt to your professor."
                            AttendanceStatus.REJECTED -> "Face verification failed. Please contact your professor and show this attempt receipt."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text("Back to Dashboard", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
    }
}
