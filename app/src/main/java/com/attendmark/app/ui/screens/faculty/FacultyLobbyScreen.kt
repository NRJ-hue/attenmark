package com.attendmark.app.ui.screens.faculty

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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacultyLobbyScreen(
    onOpenCourse: (Course) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val repo = remember { FirestoreRepository() }
    val scope = rememberCoroutineScope()

    val userName by prefs.userName.collectAsState(initial = "Faculty")
    val userEmail by prefs.userEmail.collectAsState(initial = "")

    val courses = remember { mutableStateListOf<Course>() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCourseName by remember { mutableStateOf("") }
    var createdCourse by remember { mutableStateOf<Course?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    // Load courses for this faculty
    LaunchedEffect(userEmail) {
        if (userEmail.isNotBlank()) {
            repo.getCoursesForFaculty(userEmail) { list ->
                courses.clear()
                courses.addAll(list)
            }
        }
    }

    // Create Course Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newCourseName = ""; createError = null },
            title = { Text("Create New Course") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the name of your course. A unique 6-letter join code will be generated automatically.")
                    OutlinedTextField(
                        value = newCourseName,
                        onValueChange = { newCourseName = it; createError = null },
                        label = { Text("Course Name (e.g. Software Engineering)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (createError != null) {
                        Text(createError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isCreating,
                    onClick = {
                        if (newCourseName.isBlank()) { createError = "Course name cannot be empty."; return@Button }
                        isCreating = true
                        repo.createCourse(
                            name = newCourseName.trim(),
                            facultyEmail = userEmail,
                            onSuccess = { course ->
                                isCreating = false
                                showCreateDialog = false
                                createdCourse = course
                                newCourseName = ""
                            },
                            onFailure = { e ->
                                isCreating = false
                                createError = e.message ?: "Failed to create course."
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) { Text(if (isCreating) "Creating..." else "Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newCourseName = ""; createError = null }) { Text("Cancel") }
            }
        )
    }

    // Show created course code dialog
    createdCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { createdCourse = null },
            title = { Text("Course Created! 🎉") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Share this code with your students so they can join \"${course.name}\":", textAlign = TextAlign.Center)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PrimaryBlue.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = course.classCode,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            letterSpacing = 6.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { createdCourse = null }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                    Text("Got it!")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Courses", color = Color.White) },
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
                onClick = { showCreateDialog = true },
                containerColor = PrimaryBlue,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Course")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceLight)
                .padding(paddingValues)
        ) {
            // Header greeting
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(PrimaryBlue, PrimaryBlueLight)))
                    .padding(16.dp)
            ) {
                Column {
                    Text("Welcome, $userName", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Tap a course to manage attendance", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (courses.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📚", fontSize = 56.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("No Courses Yet", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "Tap the + button to create your first course and get a join code for your students.",
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
                        CourseCard(course = course, onClick = { onOpenCourse(course) })
                    }
                }
            }
        }
    }
}

@Composable
fun CourseCard(course: Course, onClick: () -> Unit) {
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
                    .background(PrimaryBlue.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("📖", fontSize = 26.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("Code: ${course.classCode}", style = MaterialTheme.typography.bodySmall, color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                Text("${course.enrolledStudents.size} student(s) enrolled", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}
