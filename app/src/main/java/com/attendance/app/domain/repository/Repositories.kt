package com.attendance.app.domain.repository

import com.attendance.app.data.model.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────
//  Auth Repository
// ─────────────────────────────────────────────
interface AuthRepository {
    val currentUser: User?
    val currentUserId: String?
    fun observeAuthState(): Flow<User?>
    suspend fun loginWithEmail(email: String, password: String): Resource<User>
    suspend fun registerUser(email: String, password: String, displayName: String, role: UserRole, studentId: String, groupId: String): Resource<User>
    suspend fun logout()
    suspend fun getUserProfile(uid: String): Resource<User>
    suspend fun updateUserProfile(user: User): Resource<Unit>
}

// ─────────────────────────────────────────────
//  Session Repository
// ─────────────────────────────────────────────
interface SessionRepository {
    suspend fun createSession(session: Session): Resource<Session>
    suspend fun getSessionByToken(token: String): Resource<Session>
    suspend fun getSessionById(sessionId: String): Resource<Session>
    suspend fun closeSession(sessionId: String): Resource<Unit>
    fun observeActiveSessions(managerId: String): Flow<List<Session>>
    fun observeSessionAttendees(sessionId: String): Flow<List<Attendance>>
    suspend fun getSessionHistory(managerId: String): Resource<List<Session>>
}

// ─────────────────────────────────────────────
//  Attendance Repository
// ─────────────────────────────────────────────
interface AttendanceRepository {
    suspend fun markAttendance(attendance: Attendance): Resource<Attendance>
    suspend fun hasStudentMarkedAttendance(sessionId: String, studentId: String): Boolean
    fun observeStudentAttendance(studentId: String): Flow<List<Attendance>>
    suspend fun getAttendanceBySession(sessionId: String): Resource<List<Attendance>>
    suspend fun getStudentAttendanceHistory(studentId: String): Resource<List<Attendance>>
    suspend fun exportSessionAttendance(sessionId: String): Resource<String> // returns CSV string
}
