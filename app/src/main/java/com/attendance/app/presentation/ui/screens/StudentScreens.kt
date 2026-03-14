package com.attendance.app.presentation.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.app.data.model.Attendance
import com.attendance.app.data.model.AttendanceStatus
import com.attendance.app.presentation.ui.theme.*
import com.attendance.app.presentation.viewmodel.AuthViewModel
import com.attendance.app.presentation.viewmodel.ScanState
import com.attendance.app.presentation.viewmodel.StudentViewModel
import com.attendance.app.utils.QRCodeScannerView
import com.google.accompanist.permissions.*
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
//  STUDENT DASHBOARD
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDashboardScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onLogout: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
    studentViewModel: StudentViewModel = hiltViewModel()
) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val history by studentViewModel.attendanceHistory.collectAsStateWithLifecycle()

    // Calcul stats
    val presentCount = history.count { it.status == AttendanceStatus.PRESENT }
    val totalCount = history.size
    val attendanceRate = if (totalCount > 0) (presentCount * 100 / totalCount) else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bonjour 👋", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(0.8f))
                        Text(
                            text = (authState as? com.attendance.app.presentation.viewmodel.AuthState.Authenticated)?.user?.displayName ?: "Étudiant",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, "Déconnexion", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToScan,
                icon = { Icon(Icons.Default.QrCodeScanner, "Scanner") },
                text = { Text("Scanner QR") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Stats banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem("Présences", presentCount.toString(), Icons.Default.CheckCircle, AccentGreen)
                    VerticalDivider(modifier = Modifier.height(40.dp))
                    StatItem("Séances", totalCount.toString(), Icons.Default.EventNote, PrimaryBlue)
                    VerticalDivider(modifier = Modifier.height(40.dp))
                    StatItem("Taux", "$attendanceRate%", Icons.Default.TrendingUp,
                        if (attendanceRate >= 75) AccentGreen else ErrorRed)
                }
            }

            // Quick actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Scanner",
                    subtitle = "Pointer une présence",
                    icon = Icons.Default.QrCodeScanner,
                    color = PrimaryBlue,
                    onClick = onNavigateToScan
                )
                QuickActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Historique",
                    subtitle = "Voir toutes mes présences",
                    icon = Icons.Default.History,
                    color = SecondaryTeal,
                    onClick = onNavigateToHistory
                )
            }

            // Recent attendances
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Récentes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onNavigateToHistory) { Text("Tout voir") }
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.EventBusy, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Aucune présence enregistrée", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                history.take(5).forEach { attendance ->
                    AttendanceListItem(attendance = attendance, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            Spacer(modifier = Modifier.height(80.dp)) // FAB spacing
        }
    }
}

// ─────────────────────────────────────────────
//  STUDENT SCAN SCREEN
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun StudentScanScreen(
    activity: FragmentActivity,
    onBack: () -> Unit,
    viewModel: StudentViewModel = hiltViewModel()
) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    var showResultDialog by remember { mutableStateOf(false) }

    LaunchedEffect(scanState) {
        if (scanState !is ScanState.Idle && scanState !is ScanState.Processing) {
            showResultDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner le QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Retour") }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                cameraPermission.status.isGranted -> {
                    // Scanner view
                    QRCodeScannerView(
                        modifier = Modifier.fillMaxSize(),
                        onQRCodeScanned = { token ->
                            if (scanState == ScanState.Idle) {
                                viewModel.onQRScanned(token, activity)
                            }
                        },
                        onError = { /* handled in state */ }
                    )

                    // Overlay guide
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = "Placez le QR Code dans le cadre",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Processing overlay
                    AnimatedVisibility(
                        visible = scanState == ScanState.Processing,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(56.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Validation en cours...", style = MaterialTheme.typography.titleMedium)
                                Text("Vérification biométrique", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                cameraPermission.status.shouldShowRationale -> {
                    PermissionRationale(
                        message = "La caméra est nécessaire pour scanner les QR Codes de présence.",
                        onRequest = { cameraPermission.launchPermissionRequest() }
                    )
                }
                else -> {
                    LaunchedEffect(Unit) { cameraPermission.launchPermissionRequest() }
                    PermissionRationale(
                        message = "Permission caméra requise pour scanner les QR Codes.",
                        onRequest = { cameraPermission.launchPermissionRequest() }
                    )
                }
            }
        }
    }

    // Result dialog
    if (showResultDialog) {
        AttendanceScanResultDialog(
            scanState = scanState,
            onDismiss = {
                showResultDialog = false
                viewModel.resetScanState()
                if (scanState == ScanState.Success) onBack()
            }
        )
    }
}

// ─────────────────────────────────────────────
//  STUDENT HISTORY
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentHistoryScreen(
    onBack: () -> Unit,
    viewModel: StudentViewModel = hiltViewModel()
) {
    val history by viewModel.attendanceHistory.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique des présences") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Retour") } }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.EventBusy, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("Aucune présence enregistrée", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { attendance ->
                    AttendanceListItem(attendance = attendance)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  REUSABLE COMPONENTS
// ─────────────────────────────────────────────
@Composable
fun StatItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun QuickActionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AttendanceListItem(attendance: Attendance, modifier: Modifier = Modifier) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()) }
    val statusColor = when (attendance.status) {
        AttendanceStatus.PRESENT -> AccentGreen
        AttendanceStatus.ABSENT -> ErrorRed
        AttendanceStatus.LATE -> WarningOrange
        AttendanceStatus.EXCUSED -> SecondaryTeal
    }
    val statusLabel = when (attendance.status) {
        AttendanceStatus.PRESENT -> "Présent"
        AttendanceStatus.ABSENT -> "Absent"
        AttendanceStatus.LATE -> "Retard"
        AttendanceStatus.EXCUSED -> "Excusé"
    }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (attendance.status == AttendanceStatus.PRESENT) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    null, tint = statusColor, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(attendance.courseCode.ifBlank { "Cours" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    attendance.markedAt?.toDate()?.let { sdf.format(it) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (attendance.biometricValidated) SmallBadge("🔒 Bio", AccentGreen)
                    if (attendance.gpsValidated) SmallBadge("📍 GPS", SecondaryTeal)
                }
            }
            AssistChip(
                onClick = {},
                label = { Text(statusLabel, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = statusColor.copy(alpha = 0.12f),
                    labelColor = statusColor
                )
            )
        }
    }
}

@Composable
fun SmallBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
fun PermissionRationale(message: String, onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Permission requise", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest, shape = RoundedCornerShape(12.dp)) {
                Text("Autoriser la caméra")
            }
        }
    }
}

@Composable
fun AttendanceScanResultDialog(
    scanState: ScanState,
    onDismiss: () -> Unit
) {
    val (icon, title, message, color) = when (scanState) {
        is ScanState.Success -> DialogContent(Icons.Default.CheckCircle, "Présence validée !", "Votre présence a été enregistrée avec succès.", AccentGreen)
        is ScanState.AlreadyMarked -> DialogContent(Icons.Default.Info, "Déjà enregistré", "Votre présence a déjà été marquée pour cette séance.", SecondaryTeal)
        is ScanState.Error -> DialogContent(Icons.Default.Error, "Erreur", (scanState as ScanState.Error).message, ErrorRed)
        is ScanState.Cancelled -> DialogContent(Icons.Default.Cancel, "Annulé", "L'authentification a été annulée.", WarningOrange)
        else -> return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null, tint = color, modifier = Modifier.size(48.dp)) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("OK") }
        }
    )
}

private data class DialogContent(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val message: String,
    val color: Color
)
