package com.attendance.app.presentation.viewmodel

import app.cash.turbine.test
import com.attendance.app.data.model.*
import com.attendance.app.domain.repository.AttendanceRepository
import com.attendance.app.domain.repository.AuthRepository
import com.attendance.app.domain.usecase.ValidateAttendanceUseCase
import com.attendance.app.utils.BiometricPromptManager
import com.attendance.app.utils.LocationManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @MockK
    private lateinit var authRepository: AuthRepository

    private lateinit var viewModel: AuthViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    private val mockUser = User(
        uid = "uid_001",
        email = "prof@univ.dz",
        displayName = "Pr. Meziane",
        role = UserRole.MANAGER
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        every { authRepository.observeAuthState() } returns flowOf(null)
        viewModel = AuthViewModel(authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Unauthenticated when no user`() = runTest {
        viewModel.authState.test {
            assertEquals(AuthState.Unauthenticated, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state is Authenticated when user exists`() = runTest {
        every { authRepository.observeAuthState() } returns flowOf(mockUser)
        val vm = AuthViewModel(authRepository)

        vm.authState.test {
            assertEquals(AuthState.Authenticated(mockUser), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `login emits LoginEvent Success on valid credentials`() = runTest {
        coEvery {
            authRepository.loginWithEmail("prof@univ.dz", "password123")
        } returns Resource.Success(mockUser)

        viewModel.loginEvent.test {
            viewModel.login("prof@univ.dz", "password123")
            val event = awaitItem()
            assertTrue(event is LoginEvent.Success)
            assertEquals(mockUser, (event as LoginEvent.Success).user)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `login emits LoginEvent Error on invalid credentials`() = runTest {
        coEvery {
            authRepository.loginWithEmail(any(), any())
        } returns Resource.Error("Email ou mot de passe incorrect")

        viewModel.loginEvent.test {
            viewModel.login("wrong@email.com", "badpass")
            val event = awaitItem()
            assertTrue(event is LoginEvent.Error)
            assertEquals("Email ou mot de passe incorrect", (event as LoginEvent.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `logout calls authRepository logout`() = runTest {
        coEvery { authRepository.logout() } just Runs
        viewModel.logout()
        coVerify(exactly = 1) { authRepository.logout() }
    }

    @Test
    fun `register with STUDENT role calls repository with correct params`() = runTest {
        coEvery {
            authRepository.registerUser(any(), any(), any(), any(), any(), any())
        } returns Resource.Success(mockUser.copy(role = UserRole.STUDENT))

        viewModel.loginEvent.test {
            viewModel.register("etu@univ.dz", "pass123", "Amira", UserRole.STUDENT, "MAT001", "G2")
            val event = awaitItem()
            assertTrue(event is LoginEvent.Success)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            authRepository.registerUser("etu@univ.dz", "pass123", "Amira", UserRole.STUDENT, "MAT001", "G2")
        }
    }
}

// ─────────────────────────────────────────────
//  STUDENT VIEW MODEL TESTS
// ─────────────────────────────────────────────
@OptIn(ExperimentalCoroutinesApi::class)
class StudentViewModelTest {

    @MockK private lateinit var authRepository: AuthRepository
    @MockK private lateinit var attendanceRepository: AttendanceRepository
    @MockK private lateinit var validateAttendanceUseCase: ValidateAttendanceUseCase
    @MockK private lateinit var biometricManager: BiometricPromptManager
    @MockK private lateinit var locationManager: LocationManager

    private lateinit var viewModel: StudentViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    private val student = User(uid = "s001", email = "ali@univ.dz", displayName = "Ali", role = UserRole.STUDENT, studentId = "MAT001")

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { authRepository.currentUserId } returns "s001"
        every { authRepository.currentUser } returns student
        every { attendanceRepository.observeStudentAttendance("s001") } returns flowOf(emptyList())
        every { biometricManager.resultFlow } returns flowOf()

        viewModel = StudentViewModel(
            authRepository, attendanceRepository,
            validateAttendanceUseCase, biometricManager, locationManager
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial scan state is Idle`() = runTest {
        viewModel.scanState.test {
            assertEquals(ScanState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `attendance history is loaded on init`() = runTest {
        val mockHistory = listOf(
            Attendance(attendanceId = "a1", sessionId = "s1", studentId = "s001", status = AttendanceStatus.PRESENT),
            Attendance(attendanceId = "a2", sessionId = "s2", studentId = "s001", status = AttendanceStatus.ABSENT)
        )
        every { attendanceRepository.observeStudentAttendance("s001") } returns flowOf(mockHistory)
        every { biometricManager.resultFlow } returns flowOf()

        val vm = StudentViewModel(authRepository, attendanceRepository, validateAttendanceUseCase, biometricManager, locationManager)

        vm.attendanceHistory.test {
            assertEquals(mockHistory, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `resetScanState sets state back to Idle`() = runTest {
        viewModel.resetScanState()
        viewModel.scanState.test {
            assertEquals(ScanState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
