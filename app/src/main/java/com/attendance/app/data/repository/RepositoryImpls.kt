package com.attendance.app.data.repository

import com.attendance.app.data.model.*
import com.attendance.app.domain.repository.AttendanceRepository
import com.attendance.app.domain.repository.AuthRepository
import com.attendance.app.domain.repository.SessionRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────
//  AUTH REPOSITORY IMPL
// ─────────────────────────────────────────────
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : AuthRepository {

    override val currentUser: User?
        get() = firebaseAuth.currentUser?.let {
            User(uid = it.uid, email = it.email ?: "", displayName = it.displayName ?: "")
        }

    override val currentUserId: String?
        get() = firebaseAuth.currentUser?.uid

    override fun observeAuthState(): Flow<User?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val fbUser = auth.currentUser
            if (fbUser == null) {
                trySend(null)
            } else {
                // Fetch full profile asynchronously
                firestore.collection("users").document(fbUser.uid).get()
                    .addOnSuccessListener { doc ->
                        if (doc.exists()) {
                            trySend(User.fromMap(fbUser.uid, doc.data ?: emptyMap()))
                        } else {
                            trySend(User(uid = fbUser.uid, email = fbUser.email ?: ""))
                        }
                    }
                    .addOnFailureListener { trySend(User(uid = fbUser.uid, email = fbUser.email ?: "")) }
            }
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    override suspend fun loginWithEmail(email: String, password: String): Resource<User> {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Resource.Error("Utilisateur introuvable")
            getUserProfile(uid)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur de connexion")
        }
    }

    override suspend fun registerUser(
        email: String, password: String, displayName: String,
        role: UserRole, studentId: String, groupId: String
    ): Resource<User> {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Resource.Error("Création échouée")
            val user = User(uid = uid, email = email, displayName = displayName,
                role = role, studentId = studentId, groupId = groupId)
            firestore.collection("users").document(uid).set(user.toMap()).await()
            Resource.Success(user)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur d'inscription")
        }
    }

    override suspend fun logout() {
        firebaseAuth.signOut()
    }

    override suspend fun getUserProfile(uid: String): Resource<User> {
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            if (doc.exists()) {
                Resource.Success(User.fromMap(uid, doc.data ?: emptyMap()))
            } else {
                Resource.Error("Profil introuvable")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur de chargement du profil")
        }
    }

    override suspend fun updateUserProfile(user: User): Resource<Unit> {
        return try {
            firestore.collection("users").document(user.uid).update(user.toMap()).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur de mise à jour")
        }
    }
}

// ─────────────────────────────────────────────
//  SESSION REPOSITORY IMPL
// ─────────────────────────────────────────────
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : SessionRepository {

    private val sessionsRef = firestore.collection("sessions")

    override suspend fun createSession(session: Session): Resource<Session> {
        return try {
            val docRef = sessionsRef.add(session.toMap()).await()
            Resource.Success(session.copy(sessionId = docRef.id))
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur création séance")
        }
    }

    override suspend fun getSessionByToken(token: String): Resource<Session> {
        return try {
            val query = sessionsRef
                .whereEqualTo("qrToken", token)
                .whereEqualTo("status", SessionStatus.ACTIVE.name)
                .limit(1)
                .get().await()
            if (query.isEmpty) {
                Resource.Error("Séance non trouvée ou expirée")
            } else {
                val doc = query.documents.first()
                Resource.Success(Session.fromMap(doc.id, doc.data ?: emptyMap()))
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur de recherche de séance")
        }
    }

    override suspend fun getSessionById(sessionId: String): Resource<Session> {
        return try {
            val doc = sessionsRef.document(sessionId).get().await()
            if (doc.exists()) {
                Resource.Success(Session.fromMap(doc.id, doc.data ?: emptyMap()))
            } else {
                Resource.Error("Séance introuvable")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur")
        }
    }

    override suspend fun closeSession(sessionId: String): Resource<Unit> {
        return try {
            sessionsRef.document(sessionId).update(
                mapOf(
                    "status" to SessionStatus.CLOSED.name,
                    "closedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur fermeture séance")
        }
    }

    override fun observeActiveSessions(managerId: String): Flow<List<Session>> = callbackFlow {
        val listener = sessionsRef
            .whereEqualTo("managerId", managerId)
            .whereEqualTo("status", SessionStatus.ACTIVE.name)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                val sessions = snapshot?.documents?.map { doc ->
                    Session.fromMap(doc.id, doc.data ?: emptyMap())
                } ?: emptyList()
                trySend(sessions)
            }
        awaitClose { listener.remove() }
    }

    override fun observeSessionAttendees(sessionId: String): Flow<List<Attendance>> = callbackFlow {
        val listener = firestore.collection("attendances")
            .whereEqualTo("sessionId", sessionId)
            .orderBy("markedAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                val attendances = snapshot?.documents?.map { doc ->
                    Attendance.fromMap(doc.id, doc.data ?: emptyMap())
                } ?: emptyList()
                trySend(attendances)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getSessionHistory(managerId: String): Resource<List<Session>> {
        return try {
            val query = sessionsRef
                .whereEqualTo("managerId", managerId)
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(50)
                .get().await()
            val sessions = query.documents.map { doc ->
                Session.fromMap(doc.id, doc.data ?: emptyMap())
            }
            Resource.Success(sessions)
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur chargement historique")
        }
    }
}

// ─────────────────────────────────────────────
//  ATTENDANCE REPOSITORY IMPL
// ─────────────────────────────────────────────
@Singleton
class AttendanceRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : AttendanceRepository {

    private val attendancesRef = firestore.collection("attendances")

    override suspend fun markAttendance(attendance: Attendance): Resource<Attendance> {
        return try {
            val docRef = attendancesRef.add(attendance.toMap()).await()
            Resource.Success(attendance.copy(attendanceId = docRef.id))
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur enregistrement présence")
        }
    }

    override suspend fun hasStudentMarkedAttendance(sessionId: String, studentId: String): Boolean {
        return try {
            val query = attendancesRef
                .whereEqualTo("sessionId", sessionId)
                .whereEqualTo("studentId", studentId)
                .limit(1)
                .get().await()
            !query.isEmpty
        } catch (e: Exception) {
            false
        }
    }

    override fun observeStudentAttendance(studentId: String): Flow<List<Attendance>> = callbackFlow {
        val listener = attendancesRef
            .whereEqualTo("studentId", studentId)
            .orderBy("markedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                val list = snapshot?.documents?.map { doc ->
                    Attendance.fromMap(doc.id, doc.data ?: emptyMap())
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getAttendanceBySession(sessionId: String): Resource<List<Attendance>> {
        return try {
            val query = attendancesRef
                .whereEqualTo("sessionId", sessionId)
                .orderBy("markedAt", Query.Direction.ASCENDING)
                .get().await()
            Resource.Success(query.documents.map { Attendance.fromMap(it.id, it.data ?: emptyMap()) })
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur")
        }
    }

    override suspend fun getStudentAttendanceHistory(studentId: String): Resource<List<Attendance>> {
        return try {
            val query = attendancesRef
                .whereEqualTo("studentId", studentId)
                .orderBy("markedAt", Query.Direction.DESCENDING)
                .limit(100)
                .get().await()
            Resource.Success(query.documents.map { Attendance.fromMap(it.id, it.data ?: emptyMap()) })
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur historique")
        }
    }

    override suspend fun exportSessionAttendance(sessionId: String): Resource<String> {
        return try {
            val result = getAttendanceBySession(sessionId)
            if (result is Resource.Error) return result
            val attendances = (result as Resource.Success).data
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val sb = StringBuilder()
            sb.appendLine("Matricule,Nom,Statut,Biométrie,GPS,Heure")
            attendances.forEach { a ->
                val time = a.markedAt?.toDate()?.let { sdf.format(it) } ?: "N/A"
                sb.appendLine("${a.studentMatricule},${a.studentName},${a.status.name},${a.biometricValidated},${a.gpsValidated},$time")
            }
            Resource.Success(sb.toString())
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "Erreur export")
        }
    }
}
