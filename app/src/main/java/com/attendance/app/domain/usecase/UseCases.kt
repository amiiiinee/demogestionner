package com.attendance.app.domain.usecase

import android.location.Location
import com.attendance.app.data.model.*
import com.attendance.app.domain.repository.AttendanceRepository
import com.attendance.app.domain.repository.SessionRepository
import com.google.firebase.Timestamp
import java.util.*
import javax.inject.Inject
import kotlin.math.*

// ─────────────────────────────────────────────
//  VALIDATE ATTENDANCE USE CASE
//  Orchestre toute la logique de validation de présence
// ─────────────────────────────────────────────
class ValidateAttendanceUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val attendanceRepository: AttendanceRepository
) {
    /**
     * @param qrToken         Token lu depuis le QR Code
     * @param student         Profil de l'étudiant
     * @param biometricOk     Résultat biométrique
     * @param studentLocation Localisation GPS de l'étudiant (nullable)
     */
    suspend operator fun invoke(
        qrToken: String,
        student: User,
        biometricOk: Boolean,
        studentLocation: Location?
    ): AttendanceResult {

        // 1. Vérifier la biométrie
        if (!biometricOk) return AttendanceResult.BiometricFailed

        // 2. Récupérer la session via le token QR
        val sessionResult = sessionRepository.getSessionByToken(qrToken)
        if (sessionResult is Resource.Error) return AttendanceResult.SessionNotFound

        val session = (sessionResult as Resource.Success).data

        // 3. Vérifier que la session est active
        if (session.status != SessionStatus.ACTIVE) return AttendanceResult.SessionExpired

        // 4. Vérifier expiration du QR (si défini)
        session.qrExpiresAt?.let { expiresAt ->
            if (Timestamp.now().seconds > expiresAt.seconds) return AttendanceResult.SessionExpired
        }

        // 5. Vérifier si déjà marqué
        val alreadyMarked = attendanceRepository.hasStudentMarkedAttendance(session.sessionId, student.uid)
        if (alreadyMarked) return AttendanceResult.AlreadyMarked

        // 6. Validation GPS (optionnel selon config session)
        var gpsValidated = false
        var distanceMeters: Double? = null
        if (session.latitude != null && session.longitude != null && studentLocation != null) {
            distanceMeters = calculateDistance(
                studentLocation.latitude, studentLocation.longitude,
                session.latitude, session.longitude
            )
            gpsValidated = distanceMeters <= session.radiusMeters
            if (!gpsValidated) return AttendanceResult.GpsOutOfRange(distanceMeters)
        }

        // 7. Enregistrer la présence
        val attendance = Attendance(
            sessionId = session.sessionId,
            studentId = student.uid,
            studentName = student.displayName,
            studentMatricule = student.studentId,
            groupId = student.groupId,
            courseCode = session.courseCode,
            status = AttendanceStatus.PRESENT,
            biometricValidated = biometricOk,
            gpsValidated = gpsValidated,
            studentLatitude = studentLocation?.latitude,
            studentLongitude = studentLocation?.longitude,
            distanceMeters = distanceMeters
        )

        return when (val result = attendanceRepository.markAttendance(attendance)) {
            is Resource.Success -> AttendanceResult.Success
            is Resource.Error -> AttendanceResult.Error(result.message)
            is Resource.Loading -> AttendanceResult.Error("En cours...")
        }
    }

    /** Calcule la distance en mètres entre deux coordonnées GPS (Haversine) */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0 // mètres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

// ─────────────────────────────────────────────
//  GENERATE QR CODE USE CASE
// ─────────────────────────────────────────────
class GenerateSessionQRUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(
        manager: User,
        courseCode: String,
        courseName: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Int = 100,
        expirationMinutes: Int = 0 // 0 = pas d'expiration
    ): Resource<Session> {

        // Token unique : UUID + timestamp pour éviter les collisions
        val qrToken = UUID.randomUUID().toString().replace("-", "") +
                System.currentTimeMillis().toString(36)

        val expiresAt = if (expirationMinutes > 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MINUTE, expirationMinutes)
            Timestamp(cal.time)
        } else null

        val session = Session(
            managerId = manager.uid,
            managerName = manager.displayName,
            courseCode = courseCode,
            courseName = courseName,
            groupId = manager.groupId,
            qrToken = qrToken,
            qrExpiresAt = expiresAt,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            status = SessionStatus.ACTIVE
        )

        return sessionRepository.createSession(session)
    }
}
