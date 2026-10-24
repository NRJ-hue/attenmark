package com.attendmark.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.attendmark.app.data.UserPreferences
import com.attendmark.app.ui.screens.enrollment.EnrollmentScreen
import com.attendmark.app.ui.screens.faculty.FacultyDashboardScreen
import com.attendmark.app.ui.screens.faculty.FacultyLobbyScreen
import com.attendmark.app.ui.screens.login.LoginScreen
import com.attendmark.app.ui.screens.student.AttendanceFlowScreen
import com.attendmark.app.ui.screens.student.AttendanceResultScreen
import com.attendmark.app.ui.screens.student.QrScannerScreen
import com.attendmark.app.ui.screens.student.StudentDashboardScreen
import com.attendmark.app.ui.screens.student.StudentLobbyScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Enrollment : Screen("enrollment")
    object FacultyLobby : Screen("faculty_lobby")
    object StudentLobby : Screen("student_lobby")
    object StudentDashboard : Screen("student_dashboard/{courseCode}/{courseName}") {
        fun createRoute(courseCode: String, courseName: String) =
            "student_dashboard/${courseCode}/${java.net.URLEncoder.encode(courseName, "UTF-8")}"
    }
    object FacultyDashboard : Screen("faculty_dashboard/{courseCode}/{courseName}") {
        fun createRoute(courseCode: String, courseName: String) =
            "faculty_dashboard/${courseCode}/${java.net.URLEncoder.encode(courseName, "UTF-8")}"
    }
    object QrScanner : Screen("qr_scanner/{courseCode}/{courseName}") {
        fun createRoute(courseCode: String, courseName: String) =
            "qr_scanner/${courseCode}/${java.net.URLEncoder.encode(courseName, "UTF-8")}"
    }
    object AttendanceFlow : Screen("attendance_flow/{courseCode}/{sessionId}") {
        fun createRoute(courseCode: String, sessionId: String) = "attendance_flow/$courseCode/$sessionId"
    }
    object AttendanceResult : Screen("attendance_result/{recordId}") {
        fun createRoute(recordId: String) = "attendance_result/$recordId"
    }
}

@Composable
fun AttendMarkApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val userPrefs = UserPreferences(context)

    val isLoggedIn by userPrefs.isLoggedIn.collectAsState(initial = false)
    val userRole by userPrefs.userRole.collectAsState(initial = "")
    val isFaceEnrolled by userPrefs.isFaceEnrolled.collectAsState(initial = false)

    val startDestination = when {
        !isLoggedIn -> Screen.Login.route
        userRole == "faculty" -> Screen.FacultyLobby.route
        !isFaceEnrolled -> Screen.Enrollment.route
        else -> Screen.StudentLobby.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onStudentLogin = {
                    navController.navigate(Screen.StudentLobby.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onFacultyLogin = {
                    navController.navigate(Screen.FacultyLobby.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onEnrollmentNeeded = {
                    navController.navigate(Screen.Enrollment.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Enrollment.route) {
            EnrollmentScreen(
                onEnrollmentComplete = {
                    navController.navigate(Screen.StudentLobby.route) {
                        popUpTo(Screen.Enrollment.route) { inclusive = true }
                    }
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.FacultyLobby.route) {
            FacultyLobbyScreen(
                onOpenCourse = { course ->
                    navController.navigate(Screen.FacultyDashboard.createRoute(course.classCode, course.name))
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.StudentLobby.route) {
            StudentLobbyScreen(
                onOpenCourse = { course ->
                    navController.navigate(Screen.StudentDashboard.createRoute(course.classCode, course.name))
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.StudentDashboard.route,
            arguments = listOf(
                navArgument("courseCode") { type = NavType.StringType },
                navArgument("courseName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val courseCode = backStackEntry.arguments?.getString("courseCode") ?: ""
            val courseName = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("courseName") ?: "", "UTF-8"
            )
            StudentDashboardScreen(
                courseCode = courseCode,
                courseName = courseName,
                onMarkAttendance = {
                    navController.navigate(Screen.QrScanner.createRoute(courseCode, courseName))
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.FacultyDashboard.route,
            arguments = listOf(
                navArgument("courseCode") { type = NavType.StringType },
                navArgument("courseName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val courseCode = backStackEntry.arguments?.getString("courseCode") ?: ""
            val courseName = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("courseName") ?: "", "UTF-8"
            )
            FacultyDashboardScreen(
                courseCode = courseCode,
                courseName = courseName,
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.QrScanner.route,
            arguments = listOf(
                navArgument("courseCode") { type = NavType.StringType },
                navArgument("courseName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val courseCode = backStackEntry.arguments?.getString("courseCode") ?: ""
            val courseName = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("courseName") ?: "", "UTF-8"
            )
            QrScannerScreen(
                courseCode = courseCode,
                courseName = courseName,
                onScanSuccess = { validatedSessionId ->
                    navController.navigate(Screen.AttendanceFlow.createRoute(courseCode, validatedSessionId)) {
                        popUpTo(Screen.QrScanner.route) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.AttendanceFlow.route,
            arguments = listOf(
                navArgument("courseCode") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val courseCode = backStackEntry.arguments?.getString("courseCode") ?: "CS101"
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            AttendanceFlowScreen(
                courseCode = courseCode,
                sessionId = sessionId,
                onResult = { recordId ->
                    navController.navigate(Screen.AttendanceResult.createRoute(recordId)) {
                        popUpTo(Screen.AttendanceFlow.route) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.AttendanceResult.route,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId") ?: ""
            AttendanceResultScreen(
                recordId = recordId,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

