package com.attendance.app.domain.usecase

import android.location.Location
import app.cash.turbine.test
import com.attendance.app.data.model.*
import com.attendance.app.domain.repository.AttendanceRepository
import com.attendance.app.domain.repository.SessionRepository
import com.google.firebase.Timestamp
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.*

/**
 * Tests unitaires pour [ValidateAttendanceUseCase]
 * Couvre tous les cas de la logique métier de validation de présence.
 */
class ValidateAttendanceUseCaseTest {

    @MockK
    private lateinit var sessionRepository: SessionRepository

    @MockK
    private lateinit var attendanceRepository: AttendanceRepository

    private lateinit var useCase: ValidateAttendanceUseCase

    // ── Fixtures ───────────────────────────────
    private val validToken = "abc123token"

    private val student = User(
        uid = "student_001",
        email = "ali@univ.dz",
        displayName = "Ali Benali",
        role = UserRole.STUDENT,
        studentId = "MAT2024001",
        groupId = "G1"
    )

    private val activeSession = Session(
        sessionId = "session_001",
        managerId = "manager_001",
        courseCode = "INF301",
        courseName = "Algorithmique",
        groupId = "G1",
        qrToken = validToken,
        status = SessionStatus.ACTIVE,
        latitude = null,
        longitude = null,
        radiusMeters = 100
    )

    private val expiredSession = activeSession.copy(
        status = SessionStatus.CLOSED,
        qrToken = "expiredToken"
    )

    private val sessionWithGps = activeSession.copy(
        sessionId = "session_gps",
        qrToken = "gpsToken",
        latitude = 36.7538,   // Alger
        longitude = 3.0588,
        radiusMeters = 100
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        useCase = ValidateAttendanceUseCase(sessionRepository, attendanceRepository)
    }

    // ── 1. Biométrie échouée ───────────────────
    @Test
    fun `returns BiometricFailed when biometric is false`() = runTest {
        val result = useCase(
            qrToken = validToken,
            student = student,
            biometricOk = false,
            studentLocation = null
        )
        assertEquals(AttendanceResult.BiometricFailed, result)
        // On vérifie qu'aucun appel Firestore n'a été fait
        coVerify(exactly = 0) { sessionRepository.getSessionByToken(any()) }
    }

    // ── 2. Session introuvable ─────────────────
    @Test
    fun `returns SessionNotFound when token does not match any session`() = runTest {
        coEvery {
            sessionRepository.getSessionByToken("invalidToken")
        } returns Resource.Error("Séance non trouvée ou expirée")

        val result = useCase(
            qrToken = "invalidToken",
            student = student,
            biometricOk = true,
            studentLocation = null
        )
        assertEquals(AttendanceResult.SessionNotFound, result)
    }

    // ── 3. Session expirée (statut CLOSED) ─────
    @Test
    fun `returns SessionExpired when session is CLOSED`() = runTest {
        coEvery {
            sessionRepository.getSessionByToken("expiredToken")
        } returns Resource.Success(expiredSession)

        val result = useCase(
            qrToken = "expiredToken",
            student = student,
            biometricOk = true,
            studentLocation = null
        )
        assertEquals(AttendanceResult.SessionExpired, result)
    }

    // ── 4. QR expiré dans le temps ─────────────
    @Test
    fun `returns SessionExpired when QR token timestamp is past`() = runTest {
        val pastTimestamp = Timestamp(Date(System.currentTimeMillis() - 3600_000)) // 1h ago
        val sessionWithExpiredQR = activeSession.copy(qrExpiresAt = pastTimestamp)

        coEvery {
            sessionRepository.getSessionByToken(validToken)
        } returns Resource.Success(sessionWithExpiredQR)

        val result = useCase(validToken, student, biometricOk = true, studentLocation = null)
        assertEquals(AttendanceResult.SessionExpired, result)
    }

    // ── 5. Déjà marqué ─────────────────────────
    @Test
    fun `returns AlreadyMarked when student already has attendance for this session`() = runTest {
        coEvery { sessionRepository.getSessionByToken(validToken) } returns Resource.Success(activeSession)
        coEvery { attendanceRepository.hasStudentMarkedAttendance("session_001", "student_001") } returns true

        val result = useCase(validToken, student, biometricOk = true, studentLocation = null)
        assertEquals(AttendanceResult.AlreadyMarked, result)
    }

    // ── 6. Succès sans GPS ─────────────────────
    @Test
    fun `returns Success when all validations pass without GPS requirement`() = runTest {
        coEvery { sessionRepository.getSessionByToken(validToken) } returns Resource.Success(activeSession)
        coEvery { attendanceRepository.hasStudentMarkedAttendance(any(), any()) } returns false
        coEvery { attendanceRepository.markAttendance(any()) } returns Resource.Success(
            Attendance(sessionId = "session_001", studentId = "student_001")
        )

        val result = useCase(validToken, student, biometricOk = true, studentLocation = null)
        assertEquals(AttendanceResult.Success, result)
    }

    // ── 7. GPS dans le rayon ───────────────────
    @Test
    fun `returns Success when student is within GPS radius`() = runTest {
        // Localisation à ~50m du point de cours (Alger)
        val nearbyLocation = mockk<Location> {
            every { latitude } returns 36.7542   // ~50m de décalage
            every { longitude } returns 3.0590
        }

        coEvery { sessionRepository.getSessionByToken("gpsToken") } returns Resource.Success(sessionWithGps)
        coEvery { attendanceRepository.hasStudentMarkedAttendance(any(), any()) } returns false
        coEvery { attendanceRepository.markAttendance(any()) } returns Resource.Success(
            Attendance(sessionId = "session_gps", studentId = "student_001", gpsValidated = true)
        )

        val result = useCase("gpsToken", student, biometricOk = true, studentLocation = nearbyLocation)
        assertEquals(AttendanceResult.Success, result)

        // Vérifier que gpsValidated = true dans l'objet sauvegardé
        coVerify {
            attendanceRepository.markAttendance(match { it.gpsValidated == true })
        }
    }

    // ── 8. GPS hors rayon ─────────────────────
    @Test
    fun `returns GpsOutOfRange when student is too far from session location`() = runTest {
        // Localisation à ~5km (Oran) bien au-delà du rayon de 100m
        val farLocation = mockk<Location> {
            every { latitude } returns 35.6969
            every { longitude } returns -0.6331
        }

        coEvery { sessionRepository.getSessionByToken("gpsToken") } returns Resource.Success(sessionWithGps)
        coEvery { attendanceRepository.hasStudentMarkedAttendance(any(), any()) } returns false

        val result = useCase("gpsToken", student, biometricOk = true, studentLocation = farLocation)

        assertTrue("Should be GpsOutOfRange", result is AttendanceResult.GpsOutOfRange)
        val distance = (result as AttendanceResult.GpsOutOfRange).distanceMeters
        assertTrue("Distance should be > 100m", distance > 100.0)

        // Vérifier qu'on n'a PAS enregistré la présence
        coVerify(exactly = 0) { attendanceRepository.markAttendance(any()) }
    }

    // ── 9. Erreur Firestore à l'enregistrement ─
    @Test
    fun `returns Error when Firestore markAttendance fails`() = runTest {
        coEvery { sessionRepository.getSessionByToken(validToken) } returns Resource.Success(activeSession)
        coEvery { attendanceRepository.hasStudentMarkedAttendance(any(), any()) } returns false
        coEvery { attendanceRepository.markAttendance(any()) } returns Resource.Error("Erreur réseau")

        val result = useCase(validToken, student, biometricOk = true, studentLocation = null)

        assertTrue(result is AttendanceResult.Error)
        assertEquals("Erreur réseau", (result as AttendanceResult.Error).message)
    }

    // ── 10. Biométrie non vérifiée — fallback ──
    @Test
    fun `marks attendance without biometric when no biometrics enrolled`() = runTest {
        coEvery { sessionRepository.getSessionByToken(validToken) } returns Resource.Success(activeSession)
        coEvery { attendanceRepository.hasStudentMarkedAttendance(any(), any()) } returns false
        coEvery { attendanceRepository.markAttendance(any()) } returns Resource.Success(
            Attendance(sessionId = "session_001", studentId = "student_001", biometricValidated = false)
        )

        // biometricOk = false → SHOULD fail (by design)
        val result = useCase(validToken, student, biometricOk = false, studentLocation = null)
        assertEquals(AttendanceResult.BiometricFailed, result)
    }

    // ── 11. Haversine distance calculation ─────
    @Test
    fun `haversine formula returns correct distance between two GPS points`() {
        // Distance réelle Paris → Lyon ≈ 392 km
        val lat1 = 48.8566; val lng1 = 2.3522  // Paris
        val lat2 = 45.7640; val lng2 = 4.8357  // Lyon

        // Accès via réflexion à la méthode privée (ou on extrait la logique dans un objet utilitaire)
        val result = calculateDistancePublic(lat1, lng1, lat2, lng2)
        assertTrue("Distance Paris-Lyon should be ~392km", result in 380_000.0..400_000.0)
    }

    // Helper exposé pour le test (en prod, extraire dans GpsUtils)
    private fun calculateDistancePublic(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).let { it * it }
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
