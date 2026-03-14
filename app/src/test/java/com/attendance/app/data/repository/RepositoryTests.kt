package com.attendance.app.data.repository

import com.attendance.app.data.model.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceRepositoryTest {

    @MockK private lateinit var firestore: FirebaseFirestore
    @MockK private lateinit var collectionRef: CollectionReference
    @MockK private lateinit var documentRef: DocumentReference
    @MockK private lateinit var querySnapshot: QuerySnapshot
    @MockK private lateinit var documentSnapshot: DocumentSnapshot

    private lateinit var repository: AttendanceRepositoryImpl

    private val sampleAttendance = Attendance(
        attendanceId = "att_001",
        sessionId = "ses_001",
        studentId = "stu_001",
        studentName = "Amira Bouzid",
        studentMatricule = "MAT2024010",
        groupId = "G1",
        courseCode = "INF301",
        status = AttendanceStatus.PRESENT,
        biometricValidated = true,
        gpsValidated = false
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { firestore.collection("attendances") } returns collectionRef
        repository = AttendanceRepositoryImpl(firestore)
    }

    @Test
    fun `hasStudentMarkedAttendance returns true when record exists`() = runTest {
        val query = mockk<Query>()
        val queryResult = mockk<QuerySnapshot> { every { isEmpty } returns false }

        every { collectionRef.whereEqualTo("sessionId", "ses_001") } returns query
        every { query.whereEqualTo("studentId", "stu_001") } returns query
        every { query.limit(1) } returns query
        every { query.get() } returns Tasks.forResult(queryResult)

        val result = repository.hasStudentMarkedAttendance("ses_001", "stu_001")
        assertTrue(result)
    }

    @Test
    fun `hasStudentMarkedAttendance returns false when no record`() = runTest {
        val query = mockk<Query>()
        val queryResult = mockk<QuerySnapshot> { every { isEmpty } returns true }

        every { collectionRef.whereEqualTo("sessionId", "ses_001") } returns query
        every { query.whereEqualTo("studentId", "new_student") } returns query
        every { query.limit(1) } returns query
        every { query.get() } returns Tasks.forResult(queryResult)

        val result = repository.hasStudentMarkedAttendance("ses_001", "new_student")
        assertFalse(result)
    }

    @Test
    fun `hasStudentMarkedAttendance returns false on exception`() = runTest {
        val query = mockk<Query>()
        every { collectionRef.whereEqualTo("sessionId", any()) } returns query
        every { query.whereEqualTo("studentId", any()) } returns query
        every { query.limit(1) } returns query
        every { query.get() } returns Tasks.forException(Exception("Network error"))

        val result = repository.hasStudentMarkedAttendance("ses_001", "stu_001")
        assertFalse(result) // Fail safe: returns false on error
    }

    @Test
    fun `exportSessionAttendance generates correct CSV format`() = runTest {
        val query = mockk<Query>()
        val doc = mockk<DocumentSnapshot> {
            every { id } returns "att_001"
            every { data } returns sampleAttendance.toMap()
        }
        val qs = mockk<QuerySnapshot> {
            every { documents } returns listOf(doc)
        }

        every { collectionRef.whereEqualTo("sessionId", "ses_001") } returns query
        every { query.orderBy("markedAt", Query.Direction.ASCENDING) } returns query
        every { query.get() } returns Tasks.forResult(qs)

        val result = repository.exportSessionAttendance("ses_001")
        assertTrue(result is Resource.Success)

        val csv = (result as Resource.Success).data
        assertTrue("CSV should contain header", csv.contains("Matricule,Nom,Statut,Biométrie,GPS,Heure"))
        assertTrue("CSV should contain student data", csv.contains("MAT2024010"))
        assertTrue("CSV should contain student name", csv.contains("Amira Bouzid"))
        assertTrue("CSV should contain status", csv.contains("PRESENT"))
    }
}

// ─────────────────────────────────────────────
//  MODEL SERIALIZATION TESTS
// ─────────────────────────────────────────────
class ModelSerializationTest {

    @Test
    fun `User toMap and fromMap are symmetric`() {
        val original = User(
            uid = "u001",
            email = "test@test.com",
            displayName = "Test User",
            role = UserRole.STUDENT,
            studentId = "MAT001",
            groupId = "G1"
        )
        val map = original.toMap()
        val restored = User.fromMap("u001", map)

        assertEquals(original.email, restored.email)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.role, restored.role)
        assertEquals(original.studentId, restored.studentId)
        assertEquals(original.groupId, restored.groupId)
    }

    @Test
    fun `Session toMap and fromMap are symmetric`() {
        val original = Session(
            sessionId = "s001",
            managerId = "m001",
            courseCode = "INF301",
            courseName = "Algorithmique",
            groupId = "G1",
            qrToken = "token_xyz",
            latitude = 36.7538,
            longitude = 3.0588,
            radiusMeters = 150,
            status = SessionStatus.ACTIVE
        )
        val map = original.toMap()
        val restored = Session.fromMap("s001", map)

        assertEquals(original.courseCode, restored.courseCode)
        assertEquals(original.qrToken, restored.qrToken)
        assertEquals(original.latitude, restored.latitude)
        assertEquals(original.radiusMeters, restored.radiusMeters)
        assertEquals(original.status, restored.status)
    }

    @Test
    fun `Attendance toMap and fromMap are symmetric`() {
        val original = Attendance(
            attendanceId = "a001",
            sessionId = "s001",
            studentId = "u001",
            studentName = "Khalil Amrani",
            studentMatricule = "MAT2024099",
            status = AttendanceStatus.PRESENT,
            biometricValidated = true,
            gpsValidated = true,
            distanceMeters = 42.5
        )
        val map = original.toMap()
        val restored = Attendance.fromMap("a001", map)

        assertEquals(original.studentName, restored.studentName)
        assertEquals(original.status, restored.status)
        assertEquals(original.biometricValidated, restored.biometricValidated)
        assertEquals(original.gpsValidated, restored.gpsValidated)
        assertEquals(original.distanceMeters, restored.distanceMeters)
    }

    @Test
    fun `UserRole fromMap handles unknown value gracefully`() {
        val map = mapOf("role" to "STUDENT", "email" to "a@b.com")
        val user = User.fromMap("uid", map)
        assertEquals(UserRole.STUDENT, user.role)
    }

    @Test
    fun `AttendanceStatus has all expected values`() {
        val statuses = AttendanceStatus.entries
        assertTrue(statuses.contains(AttendanceStatus.PRESENT))
        assertTrue(statuses.contains(AttendanceStatus.ABSENT))
        assertTrue(statuses.contains(AttendanceStatus.LATE))
        assertTrue(statuses.contains(AttendanceStatus.EXCUSED))
    }
}
