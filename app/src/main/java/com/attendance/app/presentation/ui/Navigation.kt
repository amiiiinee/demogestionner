package com.attendance.app.presentation.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.*
import androidx.navigation.compose.*
import com.attendance.app.data.model.UserRole
import com.attendance.app.presentation.ui.screens.*
import com.attendance.app.presentation.viewmodel.AuthState
import com.attendance.app.presentation.viewmodel.AuthViewModel

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object StudentDashboard : Screen("student_dashboard")
    data object StudentScan : Screen("student_scan")
    data object StudentHistory : Screen("student_history")
    data object ManagerDashboard : Screen("manager_dashboard")
    data object ManagerCreateSession : Screen("manager_create_session")
    data object ManagerSessionLive : Screen("manager_session_live/{sessionId}") {
        fun createRoute(sessionId: String) = "manager_session_live/$sessionId"
    }
    data object ManagerHistory : Screen("manager_history")
}

@Composable
fun AttendanceNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity

    // Auto-navigate based on auth state
    LaunchedEffect(authState) {
        when (val state = authState) {
            is AuthState.Unauthenticated -> navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
            is AuthState.Authenticated -> {
                val dest = if (state.user.role == UserRole.STUDENT) Screen.StudentDashboard.route
                           else Screen.ManagerDashboard.route
                navController.navigate(dest) { popUpTo(0) { inclusive = true } }
            }
            else -> {}
        }
    }

    NavHost(navController = navController, startDestination = Screen.Login.route) {

        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onLoginSuccess = { user ->
                    val dest = if (user.role == UserRole.STUDENT) Screen.StudentDashboard.route
                               else Screen.ManagerDashboard.route
                    navController.navigate(dest) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onRegisterSuccess = { user ->
                    val dest = if (user.role == UserRole.STUDENT) Screen.StudentDashboard.route
                               else Screen.ManagerDashboard.route
                    navController.navigate(dest) { popUpTo(0) { inclusive = true } }
                }
            )
        }

        // ── Student ──
        composable(Screen.StudentDashboard.route) {
            StudentDashboardScreen(
                onNavigateToScan = { navController.navigate(Screen.StudentScan.route) },
                onNavigateToHistory = { navController.navigate(Screen.StudentHistory.route) },
                onLogout = { authViewModel.logout() }
            )
        }
        composable(Screen.StudentScan.route) {
            StudentScanScreen(
                activity = activity,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.StudentHistory.route) {
            StudentHistoryScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── Manager ──
        composable(Screen.ManagerDashboard.route) {
            ManagerDashboardScreen(
                onCreateSession = { navController.navigate(Screen.ManagerCreateSession.route) },
                onSessionHistory = { navController.navigate(Screen.ManagerHistory.route) },
                onLogout = { authViewModel.logout() }
            )
        }
        composable(Screen.ManagerCreateSession.route) {
            ManagerCreateSessionScreen(
                onBack = { navController.popBackStack() },
                onSessionCreated = { sessionId ->
                    navController.navigate(Screen.ManagerSessionLive.createRoute(sessionId)) {
                        popUpTo(Screen.ManagerDashboard.route)
                    }
                }
            )
        }
        composable(
            route = Screen.ManagerSessionLive.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ManagerSessionLiveScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ManagerHistory.route) {
            ManagerHistoryScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
