package com.attendance.app.presentation.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendance.app.data.model.Attendance
import com.attendance.app.data.model.Session
import com.attendance.app.data.model.SessionStatus
import com.attendance.app.presentation.ui.theme.*
import com.attendance.app.presentation.viewmodel.ManagerEvent
import com.attendance.app.presentation.viewmodel.ManagerViewModel
import com.attendance.app.utils.QRCodeGenerator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
//  MANAGER DASHBOARD
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerDashboardScreen(
    onCreateSession: () -> Unit,
    onSessionHistory: () -> Unit,
    onLogout: () -> Unit,
    viewModel: ManagerViewModel = hiltViewModel()
) {
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val attendees by viewModel.attendees.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ManagerEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is ManagerEvent.SessionClosed -> snackbarHostState.showSnackbar("Séance terminée")
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tableau de bord", color = MaterialTheme.colorScheme.onPrimary) },
                actions = {
                    IconButton(onClick = onSessionHistory) {
                        Icon(Icons.Default.History, "Historique", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, "Déconnexion", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryBlue)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (currentSession == null) {
                FloatingActionButton(
                    onClick = onCreateSession,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, "Nouvelle séance")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (currentSession != null) {
                // Active session card
                ActiveSessionCard(
                    session = currentSession!!,
                    attendeeCount = attendees.size,
                    onViewLive = { /* Navigate via parent */ },
                    onClose = { viewModel.closeSession() },
                    isLoading = isLoading
                )
                // Live attendees preview
                if (attendees.isNotEmpty()) {
                    Text("Présents (${attendees.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    attendees.take(3).forEach { AttendeeRow(it) }
                    if (attendees.size > 3) {
                        TextButton(onClick = { /* navigate to live */ }) {
                            Text("Voir tous les ${attendees.size} présents →")
                        }
                    }
                }
            } else {
                // No active session
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.EventAvailable, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Aucune séance active", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Appuyez sur + pour créer une nouvelle séance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onCreateSession, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Créer une séance")
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  CREATE SESSION SCREEN
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerCreateSessionScreen(
    onBack: () -> Unit,
    onSessionCreated: (String) -> Unit,
    viewModel: ManagerViewModel = hiltViewModel()
) {
    var courseCode by remember { mutableStateOf("") }
    var courseName by remember { mutableStateOf("") }
    var useGps by remember { mutableStateOf(false) }
    var radiusMeters by remember { mutableStateOf(100f) }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ManagerEvent.SessionCreated -> onSessionCreated(event.session.sessionId)
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle séance") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Retour") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Informations du cours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = courseCode,
                onValueChange = { courseCode = it },
                label = { Text("Code du cours (ex: INF301)") },
                leadingIcon = { Icon(Icons.Default.Code, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = courseName,
                onValueChange = { courseName = it },
                label = { Text("Nom du cours") },
                leadingIcon = { Icon(Icons.Default.Book, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Divider()
            Text("Validation GPS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (useGps) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, null, tint = if (useGps) PrimaryBlue else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vérification de proximité", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text("Les étudiants doivent être proches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = useGps, onCheckedChange = { useGps = it })
                }
            }

            if (useGps) {
                Text(
                    "Rayon autorisé : ${radiusMeters.toInt()} m",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = radiusMeters,
                    onValueChange = { radiusMeters = it },
                    valueRange = 20f..500f,
                    steps = 23
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.generateSession(
                        courseCode = courseCode,
                        courseName = courseName,
                        useGps = useGps,
                        radiusMeters = radiusMeters.toInt()
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = courseCode.isNotBlank() && courseName.isNotBlank() && !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Générer le QR Code", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  MANAGER SESSION LIVE SCREEN
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerSessionLiveScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: ManagerViewModel = hiltViewModel()
) {
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val attendees by viewModel.attendees.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showCloseDialog by remember { mutableStateOf(false) }

    // Generate QR bitmap when session is available
    LaunchedEffect(currentSession) {
        currentSession?.qrToken?.let { token ->
            qrBitmap = QRCodeGenerator.generate(token, 512)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ManagerEvent.SessionClosed -> onBack()
                is ManagerEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is ManagerEvent.ExportReady -> {
                    // In real app: share CSV file
                    snackbarHostState.showSnackbar("Export prêt (${event.csvContent.lines().size - 1} lignes)")
                }
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentSession?.courseName ?: "Séance en direct") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = { viewModel.exportAttendance(sessionId) }) {
                        Icon(Icons.Default.FileDownload, "Exporter")
                    }
                    IconButton(onClick = { showCloseDialog = true }) {
                        Icon(Icons.Default.StopCircle, "Terminer", tint = ErrorRed)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // QR Code card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen)
                            )
                            Text("Séance active", style = MaterialTheme.typography.labelMedium, color = AccentGreen)
                        }
                        Spacer(Modifier.height(12.dp))
                        qrBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR Code de présence",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(8.dp)
                            )
                        } ?: CircularProgressIndicator(modifier = Modifier.size(80.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = currentSession?.courseCode ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Montrez ce QR aux étudiants",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Stats
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(modifier = Modifier.weight(1f), value = attendees.size.toString(), label = "Présents", icon = Icons.Default.People, color = AccentGreen)
                    StatCard(modifier = Modifier.weight(1f), value = if (currentSession?.latitude != null) "✓" else "✗", label = "GPS actif", icon = Icons.Default.LocationOn, color = if (currentSession?.latitude != null) SecondaryTeal else MaterialTheme.colorScheme.outline)
                }
            }

            // Attendee list header
            item {
                Text("Liste des présents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            if (attendees.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.HourglassEmpty, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Text("En attente d'étudiants...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            items(attendees) { attendee ->
                AttendeeRow(attendee = attendee)
            }
        }
    }

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            icon = { Icon(Icons.Default.StopCircle, null, tint = ErrorRed, modifier = Modifier.size(40.dp)) },
            title = { Text("Terminer la séance ?", fontWeight = FontWeight.Bold) },
            text = { Text("Les étudiants ne pourront plus scanner le QR Code. ${attendees.size} présence(s) enregistrée(s).") },
            confirmButton = {
                Button(
                    onClick = { viewModel.closeSession(); showCloseDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Terminer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCloseDialog = false }, shape = RoundedCornerShape(12.dp)) {
                    Text("Annuler")
                }
            }
        )
    }
}

// ─────────────────────────────────────────────
//  MANAGER HISTORY SCREEN
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerHistoryScreen(
    onBack: () -> Unit,
    viewModel: ManagerViewModel = hiltViewModel()
) {
    val history by viewModel.sessionHistory.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ManagerEvent.ExportReady -> snackbarHostState.showSnackbar("Export CSV prêt")
                is ManagerEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique des séances") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Retour") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.EventBusy, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("Aucune séance enregistrée", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { session ->
                    SessionHistoryCard(
                        session = session,
                        onExport = { viewModel.exportAttendance(session.sessionId) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  REUSABLE MANAGER COMPONENTS
// ─────────────────────────────────────────────
@Composable
fun ActiveSessionCard(
    session: Session,
    attendeeCount: Int,
    onViewLive: () -> Unit,
    onClose: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentGreen))
                    Text("En cours", style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                }
                Text(session.courseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${session.courseCode} • $attendeeCount présent(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Stop, "Terminer", tint = ErrorRed)
                }
            }
        }
    }
}

@Composable
fun AttendeeRow(attendee: Attendance) {
    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(PrimaryBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(attendee.studentName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(attendee.studentMatricule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    attendee.markedAt?.toDate()?.let { sdf.format(it) } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (attendee.biometricValidated) Icon(Icons.Default.Fingerprint, null, modifier = Modifier.size(14.dp), tint = AccentGreen)
                    if (attendee.gpsValidated) Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(14.dp), tint = SecondaryTeal)
                }
            }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SessionHistoryCard(session: Session, onExport: () -> Unit) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()) }
    val statusColor = when (session.status) {
        SessionStatus.ACTIVE -> AccentGreen
        SessionStatus.CLOSED -> MaterialTheme.colorScheme.outline
        SessionStatus.CANCELLED -> ErrorRed
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.courseName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(session.courseCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    session.startedAt?.toDate()?.let { sdf.format(it) } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AssistChip(
                    onClick = {},
                    label = { Text(session.status.name, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = statusColor.copy(alpha = 0.12f), labelColor = statusColor)
                )
                IconButton(onClick = onExport, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.FileDownload, "Exporter", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
