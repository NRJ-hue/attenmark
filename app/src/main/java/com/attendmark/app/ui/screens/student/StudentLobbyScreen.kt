package com.attendmark.app.ui.screens.student

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendmark.app.data.Course
import com.attendmark.app.data.FirestoreRepository
import com.attendmark.app.data.UserPreferences
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentLobbyScreen(
    onOpenCourse: (Course) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val repo = remember { FirestoreRepository() }
    val scope = rememberCoroutineScope()

    val userName by prefs.userName.collectAsState(initial = "Student")
    val userEmail by prefs.userEmail.collectAsState(initial = "")

    val courses = remember { mutableStateListOf<Course>() }
    var liveCodes by remember { mutableStateOf(setOf<String>()) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var isJoining by remember { mutableStateOf(false) }
    var joinSuccess by remember { mutableStateOf<String?>(null) }

    // Load enrolled courses
    LaunchedEffect(userEmail) {
        if (userEmail.isNotBlank()) {
            repo.getCoursesForStudent(userEmail) { list ->
                courses.clear()
                courses.addAll(list)
            }
        }
    }

    // Reactively refresh live active sessions for all enrolled courses
    LaunchedEffect(courses.toList()) {
        if (courses.isNotEmpty()) {
            while (isActive) {
                val newLiveCodes = mutableSetOf<String>()
                for (course in courses) {
                    if (repo.hasActiveCourseSession(course.classCode)) {
                        newLiveCodes.add(course.classCode)
                    }
                }
                liveCodes = newLiveCodes
                kotlinx.coroutines.delay(4000)
            }
        } else {
            liveCodes = emptySet()
        }
    }

    // Join dialog
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false; joinCode = ""; joinError = null },
            title = { Text("Join a Course") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the 6-letter code your professor shared with you.")
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it.uppercase().take(6); joinError = null },
                        label = { Text("Class Code (e.g. ABCXYZ)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (joinError != null) {
                        Text(joinError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (joinSuccess != null) {
                        Text(joinSuccess!!, color = SuccessGreen, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isJoining,
                    onClick = {
                        if (joinCode.length < 6) { joinError = "Code must be 6 letters."; return@Button }
                        isJoining = true
                        repo.joinCourse(
                            classCode = joinCode,
                            studentEmail = userEmail,
                            onSuccess = { course ->
                                isJoining = false
                                joinSuccess = "Successfully joined \"${course.name}\"!"
                                joinCode = ""
                                // Dismiss after short delay handled by user
                                showJoinDialog = false
                            },
                            onFailure = { err ->
                                isJoining = false
                                joinError = err
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) { Text(if (isJoining) "Joining..." else "Join") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false; joinCode = ""; joinError = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Classes", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue),
                actions = {
                    IconButton(onClick = {
                        scope.launch { prefs.logout(); onLogout() }
                    }) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout", tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showJoinDialog = true },
                containerColor = PrimaryBlue,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Join Course")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceLight)
                .padding(paddingValues)
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(PrimaryBlue, PrimaryBlueLight)))
                    .padding(16.dp)
            ) {
                Column {
                    Text("Hello, $userName!", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Tap a class to view or mark attendance", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (courses.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🎓", fontSize = 56.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("No Classes Joined", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "Tap the + button and enter the code your professor gave you to join a class.",
                        textAlign = TextAlign.Center,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(courses) { course ->
                        val isLive = liveCodes.contains(course.classCode)
                        StudentCourseCard(
                            course = course,
                            isLive = isLive,
                            onClick = { onOpenCourse(course) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudentCourseCard(course: Course, isLive: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        if (isLive) SuccessGreen.copy(alpha = 0.15f) else PrimaryBlue.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isLive) "🟢" else "📚", fontSize = 26.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    if (isLive) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = SuccessGreen,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "LIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text("Code: ${course.classCode}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (isLive) {
                    Text("Session active — tap to mark attendance!", style = MaterialTheme.typography.bodySmall, color = SuccessGreen, fontWeight = FontWeight.SemiBold)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}
