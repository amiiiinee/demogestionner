package com.attendance.app.presentation.viewmodel

import android.location.Location
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendance.app.data.model.*
import com.attendance.app.domain.repository.AttendanceRepository
import com.attendance.app.domain.repository.AuthRepository
import com.attendance.app.domain.repository.SessionRepository
import com.attendance.app.domain.usecase.GenerateSessionQRUseCase
import com.attendance.app.domain.usecase.ValidateAttendanceUseCase
import com.attendance.app.utils.BiometricPromptManager
import com.attendance.app.utils.BiometricResult
import com.attendance.app.utils.LocationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────
//  AUTH VIEW MODEL
// ─────────────────────────────────────────────
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _loginEvent = MutableSharedFlow<LoginEvent>()
    val loginEvent: SharedFlow<LoginEvent> = _loginEvent.asSharedFlow()

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.observeAuthState().collect { user ->
                _authState.value = if (user != null) {
                    AuthState.Authenticated(user)
                } else {
                    AuthState.Unauthenticated
                }
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            when (val result = authRepository.loginWithEmail(email.trim(), password)) {
                is Resource.Success -> {
                    _authState.value = AuthState.Authenticated(result.data)
                    _loginEvent.emit(LoginEvent.Success(result.data))
                }
                is Resource.Error -> {
                    _authState.value = AuthState.Unauthenticated
                    _loginEvent.emit(LoginEvent.Error(result.message))
                }
                else -> {}
            }
        }
    }

    fun register(
        email: String, password: String, displayName: String,
        role: UserRole, studentId: String, groupId: String
    ) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            when (val result = authRepository.registerUser(email.trim(), password, displayName, role, studentId, groupId)) {
                is Resource.Success -> _loginEvent.emit(LoginEvent.Success(result.data))
                is Resource.Error -> {
                    _authState.value = AuthState.Unauthenticated
                    _loginEvent.emit(LoginEvent.Error(result.message))
                }
                else -> {}
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}

sealed class AuthState {
    data object Loading : AuthState()
    data object Unauthenticated : AuthState()
    data class Authenticated(val user: User) : AuthState()
}

sealed class LoginEvent {
    data class Success(val user: User) : LoginEvent()
    data class Error(val message: String) : LoginEvent()
}

// ─────────────────────────────────────────────
//  STUDENT VIEW MODEL
// ─────────────────────────────────────────────
@HiltViewModel
class StudentViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val attendanceRepository: AttendanceRepository,
    private val validateAttendanceUseCase: ValidateAttendanceUseCase,
    private val biometricManager: BiometricPromptManager,
    private val locationManager: LocationManager
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _attendanceHistory = MutableStateFlow<List<Attendance>>(emptyList())
    val attendanceHistory: StateFlow<List<Attendance>> = _attendanceHistory.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var pendingQrToken: String? = null

    init {
        loadAttendanceHistory()
        observeBiometricResult()
    }

    private fun loadAttendanceHistory() {
        val uid = authRepository.currentUserId ?: return
        viewModelScope.launch {
            attendanceRepository.observeStudentAttendance(uid).collect { list ->
                _attendanceHistory.value = list
            }
        }
    }

    /** Appelé quand le QR est scanné — déclenche la biométrie */
    fun onQRScanned(token: String, activity: FragmentActivity) {
        if (_scanState.value is ScanState.Processing) return
        pendingQrToken = token
        _scanState.value = ScanState.Processing
        biometricManager.showBiometricPrompt(
            activity = activity,
            title = "Confirmer votre présence",
            subtitle = "Authentification requise",
            description = "Validez votre empreinte pour marquer votre présence"
        )
    }

    private fun observeBiometricResult() {
        viewModelScope.launch {
            biometricManager.resultFlow.collect { result ->
                val token = pendingQrToken ?: run {
                    _scanState.value = ScanState.Error("Token QR manquant")
                    return@collect
                }
                when (result) {
                    is BiometricResult.Success -> processAttendance(token, biometricOk = true)
                    is BiometricResult.Cancelled -> _scanState.value = ScanState.Cancelled
                    is BiometricResult.NoEnrolledBiometrics -> processAttendance(token, biometricOk = false)
                    is BiometricResult.Error -> _scanState.value = ScanState.Error("Biométrie : ${result.message}")
                    else -> _scanState.value = ScanState.Error("Authentification biométrique échouée")
                }
            }
        }
    }

    private suspend fun processAttendance(token: String, biometricOk: Boolean) {
        val student = authRepository.currentUser ?: run {
            _scanState.value = ScanState.Error("Utilisateur non connecté")
            return
        }
        // Récupérer la position GPS
        val location: Location? = try {
            locationManager.getCurrentLocation() ?: locationManager.getLastKnownLocation()
        } catch (e: Exception) { null }

        val attendanceResult = validateAttendanceUseCase(token, student, biometricOk, location)
        _scanState.value = when (attendanceResult) {
            is AttendanceResult.Success -> ScanState.Success
            is AttendanceResult.AlreadyMarked -> ScanState.AlreadyMarked
            is AttendanceResult.SessionExpired -> ScanState.Error("La séance a expiré")
            is AttendanceResult.SessionNotFound -> ScanState.Error("Séance introuvable")
            is AttendanceResult.BiometricFailed -> ScanState.Error("Biométrie non validée")
            is AttendanceResult.GpsOutOfRange -> ScanState.Error("Vous êtes trop loin (${attendanceResult.distanceMeters.toInt()}m)")
            is AttendanceResult.Error -> ScanState.Error(attendanceResult.message)
        }
        pendingQrToken = null
    }

    fun resetScanState() { _scanState.value = ScanState.Idle }
}

sealed class ScanState {
    data object Idle : ScanState()
    data object Processing : ScanState()
    data object Success : ScanState()
    data object AlreadyMarked : ScanState()
    data object Cancelled : ScanState()
    data class Error(val message: String) : ScanState()
}

// ─────────────────────────────────────────────
//  MANAGER VIEW MODEL
// ─────────────────────────────────────────────
@HiltViewModel
class ManagerViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionRepository: SessionRepository,
    private val attendanceRepository: AttendanceRepository,
    private val generateSessionQRUseCase: GenerateSessionQRUseCase,
    private val locationManager: LocationManager
) : ViewModel() {

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val _attendees = MutableStateFlow<List<Attendance>>(emptyList())
    val attendees: StateFlow<List<Attendance>> = _attendees.asStateFlow()

    private val _sessionHistory = MutableStateFlow<List<Session>>(emptyList())
    val sessionHistory: StateFlow<List<Session>> = _sessionHistory.asStateFlow()

    private val _uiEvent = MutableSharedFlow<ManagerEvent>()
    val uiEvent: SharedFlow<ManagerEvent> = _uiEvent.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadSessionHistory()
    }

    fun generateSession(courseCode: String, courseName: String, useGps: Boolean, radiusMeters: Int) {
        val manager = authRepository.currentUser ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val location = if (useGps) {
                try { locationManager.getCurrentLocation() } catch (e: Exception) { null }
            } else null

            val result = generateSessionQRUseCase(
                manager = manager,
                courseCode = courseCode,
                courseName = courseName,
                latitude = location?.latitude,
                longitude = location?.longitude,
                radiusMeters = radiusMeters,
                expirationMinutes = 15
            )
            when (result) {
                is Resource.Success -> {
                    _currentSession.value = result.data
                    observeAttendees(result.data.sessionId)
                    _uiEvent.emit(ManagerEvent.SessionCreated(result.data))
                }
                is Resource.Error -> _uiEvent.emit(ManagerEvent.Error(result.message))
                else -> {}
            }
            _isLoading.value = false
        }
    }

    private fun observeAttendees(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.observeSessionAttendees(sessionId).collect { list ->
                _attendees.value = list
            }
        }
    }

    fun closeSession() {
        val session = _currentSession.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = sessionRepository.closeSession(session.sessionId)) {
                is Resource.Success -> {
                    _currentSession.value = null
                    _attendees.value = emptyList()
                    _uiEvent.emit(ManagerEvent.SessionClosed)
                    loadSessionHistory()
                }
                is Resource.Error -> _uiEvent.emit(ManagerEvent.Error(result.message))
                else -> {}
            }
            _isLoading.value = false
        }
    }

    fun exportAttendance(sessionId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = attendanceRepository.exportSessionAttendance(sessionId)) {
                is Resource.Success -> _uiEvent.emit(ManagerEvent.ExportReady(result.data))
                is Resource.Error -> _uiEvent.emit(ManagerEvent.Error(result.message))
                else -> {}
            }
            _isLoading.value = false
        }
    }

    private fun loadSessionHistory() {
        val managerId = authRepository.currentUserId ?: return
        viewModelScope.launch {
            when (val result = sessionRepository.getSessionHistory(managerId)) {
                is Resource.Success -> _sessionHistory.value = result.data
                else -> {}
            }
        }
    }
}

sealed class ManagerEvent {
    data class SessionCreated(val session: Session) : ManagerEvent()
    data object SessionClosed : ManagerEvent()
    data class ExportReady(val csvContent: String) : ManagerEvent()
    data class Error(val message: String) : ManagerEvent()
}
