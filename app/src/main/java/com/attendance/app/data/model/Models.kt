package com.attendance.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

// ─────────────────────────────────────────────
//  USER
// ─────────────────────────────────────────────
enum class UserRole { STUDENT, MANAGER }

data class User(
    @DocumentId val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: UserRole = UserRole.STUDENT,
    val studentId: String = "",        // matricule étudiant
    val groupId: String = "",          // groupe/classe
    val photoUrl: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    /** Conversion Firestore Map → User */
    companion object {
        fun fromMap(uid: String, map: Map<String, Any?>): User = User(
            uid = uid,
            email = map["email"] as? String ?: "",
            displayName = map["displayName"] as? String ?: "",
            role = UserRole.valueOf((map["role"] as? String) ?: UserRole.STUDENT.name),
            studentId = map["studentId"] as? String ?: "",
            groupId = map["groupId"] as? String ?: "",
            photoUrl = map["photoUrl"] as? String ?: "",
            createdAt = map["createdAt"] as? Timestamp
        )
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "email" to email,
        "displayName" to displayName,
        "role" to role.name,
        "studentId" to studentId,
        "groupId" to groupId,
        "photoUrl" to photoUrl,
        "createdAt" to createdAt
    )
}

// ─────────────────────────────────────────────
//  SESSION (séance générée par le gestionnaire)
// ─────────────────────────────────────────────
enum class SessionStatus { ACTIVE, CLOSED, CANCELLED }

data class Session(
    @DocumentId val sessionId: String = "",
    val managerId: String = "",
    val managerName: String = "",
    val courseCode: String = "",
    val courseName: String = "",
    val groupId: String = "",
    val qrToken: String = "",          // token unique embarqué dans le QR
    val qrExpiresAt: Timestamp? = null,// expiration du QR (optionnel : 15 min)
    val latitude: Double? = null,      // position GPS du gestionnaire
    val longitude: Double? = null,
    val radiusMeters: Int = 100,       // rayon de validation GPS
    val status: SessionStatus = SessionStatus.ACTIVE,
    @ServerTimestamp val startedAt: Timestamp? = null,
    val closedAt: Timestamp? = null,
    val totalStudents: Int = 0
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): Session = Session(
            sessionId = id,
            managerId = map["managerId"] as? String ?: "",
            managerName = map["managerName"] as? String ?: "",
            courseCode = map["courseCode"] as? String ?: "",
            courseName = map["courseName"] as? String ?: "",
            groupId = map["groupId"] as? String ?: "",
            qrToken = map["qrToken"] as? String ?: "",
            qrExpiresAt = map["qrExpiresAt"] as? Timestamp,
            latitude = map["latitude"] as? Double,
            longitude = map["longitude"] as? Double,
            radiusMeters = (map["radiusMeters"] as? Long)?.toInt() ?: 100,
            status = SessionStatus.valueOf((map["status"] as? String) ?: SessionStatus.ACTIVE.name),
            startedAt = map["startedAt"] as? Timestamp,
            closedAt = map["closedAt"] as? Timestamp,
            totalStudents = (map["totalStudents"] as? Long)?.toInt() ?: 0
        )
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "managerId" to managerId,
        "managerName" to managerName,
        "courseCode" to courseCode,
        "courseName" to courseName,
        "groupId" to groupId,
        "qrToken" to qrToken,
        "qrExpiresAt" to qrExpiresAt,
        "latitude" to latitude,
        "longitude" to longitude,
        "radiusMeters" to radiusMeters,
        "status" to status.name,
        "startedAt" to startedAt,
        "closedAt" to closedAt,
        "totalStudents" to totalStudents
    )
}

// ─────────────────────────────────────────────
//  ATTENDANCE (enregistrement de présence)
// ─────────────────────────────────────────────
enum class AttendanceStatus { PRESENT, ABSENT, LATE, EXCUSED }

data class Attendance(
    @DocumentId val attendanceId: String = "",
    val sessionId: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val studentMatricule: String = "",
    val groupId: String = "",
    val courseCode: String = "",
    val status: AttendanceStatus = AttendanceStatus.PRESENT,
    val biometricValidated: Boolean = false,
    val gpsValidated: Boolean = false,
    val studentLatitude: Double? = null,
    val studentLongitude: Double? = null,
    val distanceMeters: Double? = null,
    @ServerTimestamp val markedAt: Timestamp? = null
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): Attendance = Attendance(
            attendanceId = id,
            sessionId = map["sessionId"] as? String ?: "",
            studentId = map["studentId"] as? String ?: "",
            studentName = map["studentName"] as? String ?: "",
            studentMatricule = map["studentMatricule"] as? String ?: "",
            groupId = map["groupId"] as? String ?: "",
            courseCode = map["courseCode"] as? String ?: "",
            status = AttendanceStatus.valueOf((map["status"] as? String) ?: AttendanceStatus.PRESENT.name),
            biometricValidated = map["biometricValidated"] as? Boolean ?: false,
            gpsValidated = map["gpsValidated"] as? Boolean ?: false,
            studentLatitude = map["studentLatitude"] as? Double,
            studentLongitude = map["studentLongitude"] as? Double,
            distanceMeters = map["distanceMeters"] as? Double,
            markedAt = map["markedAt"] as? Timestamp
        )
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "sessionId" to sessionId,
        "studentId" to studentId,
        "studentName" to studentName,
        "studentMatricule" to studentMatricule,
        "groupId" to groupId,
        "courseCode" to courseCode,
        "status" to status.name,
        "biometricValidated" to biometricValidated,
        "gpsValidated" to gpsValidated,
        "studentLatitude" to studentLatitude,
        "studentLongitude" to studentLongitude,
        "distanceMeters" to distanceMeters,
        "markedAt" to markedAt
    )
}

// ─────────────────────────────────────────────
//  UI STATES (sealed classes helper)
// ─────────────────────────────────────────────
sealed class Resource<out T> {
    data object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : Resource<Nothing>()
}

/** Résultat de la validation de présence */
sealed class AttendanceResult {
    data object Success : AttendanceResult()
    data object AlreadyMarked : AttendanceResult()
    data object SessionExpired : AttendanceResult()
    data object SessionNotFound : AttendanceResult()
    data object BiometricFailed : AttendanceResult()
    data class GpsOutOfRange(val distanceMeters: Double) : AttendanceResult()
    data class Error(val message: String) : AttendanceResult()
}
