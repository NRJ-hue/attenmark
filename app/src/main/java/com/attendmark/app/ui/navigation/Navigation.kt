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
import com.attendmark.app.ui.screens.login.LoginScreen
import com.attendmark.app.ui.screens.student.AttendanceFlowScreen
import com.attendmark.app.ui.screens.student.AttendanceResultScreen
import com.attendmark.app.ui.screens.student.StudentDashboardScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Enrollment : Screen("enrollment")
    object StudentDashboard : Screen("student_dashboard")
    object FacultyDashboard : Screen("faculty_dashboard")
    object AttendanceFlow : Screen("attendance_flow")
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
        userRole == "faculty" -> Screen.FacultyDashboard.route
        !isFaceEnrolled -> Screen.Enrollment.route
        else -> Screen.StudentDashboard.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onStudentLogin = {
                    navController.navigate(Screen.StudentDashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onFacultyLogin = {
                    navController.navigate(Screen.FacultyDashboard.route) {
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
                    navController.navigate(Screen.StudentDashboard.route) {
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

        composable(Screen.StudentDashboard.route) {
            StudentDashboardScreen(
                onMarkAttendance = {
                    navController.navigate(Screen.AttendanceFlow.route)
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.FacultyDashboard.route) {
            FacultyDashboardScreen(
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.AttendanceFlow.route) {
            AttendanceFlowScreen(
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
                    navController.navigate(Screen.StudentDashboard.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
