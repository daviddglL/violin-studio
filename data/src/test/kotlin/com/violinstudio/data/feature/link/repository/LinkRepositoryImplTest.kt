package com.violinstudio.data.feature.link.repository

import app.cash.turbine.test
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.commons.firebase.RemoteDoc
import com.violinstudio.data.feature.link.datasource.FakeLinkRemoteDataSource
import com.violinstudio.data.feature.link.datasource.FakeTeacherFunctionsDataSource
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.profile.model.TeacherRequestStatus
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkRepositoryImplTest {
    private val remote = FakeLinkRemoteDataSource()
    private val functions = FakeTeacherFunctionsDataSource()
    private val repo = LinkRepositoryImpl(remote, functions)
    private val at = Instant.parse("2026-03-01T10:00:00Z")

    private fun linkData(
        teacher: Any? = "t1",
        student: Any? = "s1",
        ack: Any? = 1L,
        created: Any? = Timestamp(at.epochSecond, 0)
    ) = mapOf(
        "teacherUid" to teacher,
        "studentUid" to student,
        "teacherDisplayName" to "Profe",
        "studentDisplayName" to "Alumno",
        "studentInstrument" to "cello",
        "ackVersion" to ack,
        "createdAt" to created,
        "extra" to "ignorado"
    )

    @Test
    fun `observeAsStudent y observeAsTeacher consultan por su campo y mapean`() = runTest {
        val expected = TeacherLink("t1", "s1", "Profe", "Alumno", Instrument.CELLO, 1, at)
        repo.observeAsStudent("s1").test {
            remote.snapshots.emit(listOf(RemoteDoc("t1_s1", linkData(), false)))
            assertEquals(listOf(expected), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        repo.observeAsTeacher("t1").test {
            assertEquals(listOf(expected), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("student:s1", "teacher:t1"), remote.observed)
    }

    @Test
    fun `un elemento corrupto no tira la lista y los campos opcionales son tolerantes`() = runTest {
        val docs = listOf(
            RemoteDoc("a", linkData(teacher = 5L), false),
            RemoteDoc("b", linkData(created = "ayer"), false),
            RemoteDoc("c", linkData(ack = "1"), false),
            RemoteDoc("d", mapOf("nada" to 1), false),
            RemoteDoc("e", linkData() + ("studentInstrument" to "kazoo") + ("teacherDisplayName" to null), false)
        )
        repo.observeAsTeacher("t1").test {
            remote.snapshots.emit(docs)
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(null, list.single().studentInstrument)
            assertEquals("", list.single().teacherDisplayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission-denied del listener es PermissionDenied, sin red Offline y otro Unknown`() = runTest {
        remote.observeFailure = FirebaseFirestoreException("x", Code.PERMISSION_DENIED)
        repo.observeAsStudent("s1").test { assertSame(LinkFailure.PermissionDenied, awaitError()) }
        remote.observeFailure = IOException("sin red")
        repo.observeAsStudent("s1").test { assertSame(LinkFailure.Offline, awaitError()) }
        remote.observeFailure = IllegalStateException("boom")
        repo.observeAsStudent("s1").test { assertSame(LinkFailure.Unknown, awaitError()) }
    }

    @Test
    fun `redeem envia codigo y version y devuelve el uid del profesor`() = runTest {
        functions.response = mapOf("linked" to true, "teacherUid" to "t1")
        assertEquals("t1", repo.redeem("ABCDEFGH", 2).getOrThrow())
        assertEquals(listOf("redeem", "ABCDEFGH", 2), functions.calls.single())
    }

    @Test
    fun `redeem mapea el fallo del callable y una respuesta malformada es Unknown`() = runTest {
        functions.failure = FunctionsCallException("RESOURCE_EXHAUSTED", mapOf("reason" to "TEACHER_FULL"))
        assertSame(LinkFailure.TeacherFull, repo.redeem("ABCDEFGH", 1).exceptionOrNull())
        functions.failure = null
        functions.response = mapOf("linked" to true)
        assertSame(LinkFailure.Unknown, repo.redeem("ABCDEFGH", 1).exceptionOrNull())
    }

    @Test
    fun `unlink llama con ambos uids y mapea NOT_LINK_PARTY`() = runTest {
        functions.response = mapOf("unlinked" to true)
        assertTrue(repo.unlink("t1", "s1").isSuccess)
        assertEquals(listOf("unlink", "t1", "s1"), functions.calls.single())
        functions.failure = FunctionsCallException("PERMISSION_DENIED", mapOf("reason" to "NOT_LINK_PARTY"))
        assertSame(LinkFailure.PermissionDenied, repo.unlink("t1", "s1").exceptionOrNull())
    }

    @Test
    fun `createCode devuelve el codigo en claro y listCodes solo la pista`() = runTest {
        functions.response = mapOf("id" to "h1", "code" to "ABCDEFGH", "codeHint" to "GH", "expiresAt" to 1_000.0)
        assertEquals(
            TeacherCode("h1", "ABCDEFGH", "GH", Instant.ofEpochMilli(1_000)),
            repo.createCode().getOrThrow()
        )
        functions.response = mapOf(
            "codes" to listOf(
                mapOf("id" to "h1", "codeHint" to "GH", "expiresAt" to 2_000L),
                mapOf("id" to "roto", "expiresAt" to 3_000L),
                "basura"
            )
        )
        assertEquals(
            listOf(TeacherCode("h1", null, "GH", Instant.ofEpochMilli(2_000))),
            repo.listCodes().getOrThrow()
        )
    }

    @Test
    fun `listCodes sin lista y createCode malformado son Unknown, el limite se mapea`() = runTest {
        functions.response = mapOf("codes" to "no")
        assertSame(LinkFailure.Unknown, repo.listCodes().exceptionOrNull())
        functions.response = mapOf("id" to "h1")
        assertSame(LinkFailure.Unknown, repo.createCode().exceptionOrNull())
        functions.failure = FunctionsCallException("RESOURCE_EXHAUSTED", mapOf("reason" to "CODE_LIMIT_REACHED"))
        assertSame(LinkFailure.CodeLimitReached, repo.createCode().exceptionOrNull())
    }

    @Test
    fun `revokeCode envia el id`() = runTest {
        functions.response = mapOf("revoked" to true)
        assertTrue(repo.revokeCode("h1").isSuccess)
        assertEquals(listOf("revokeCode", "h1"), functions.calls.single())
    }

    @Test
    fun `requestTeacherRole devuelve el estado y mapea NOT_ELIGIBLE y la respuesta malformada`() = runTest {
        functions.response = mapOf("status" to "pending")
        assertEquals(TeacherRequestStatus.PENDING, repo.requestTeacherRole().getOrThrow())
        assertEquals(listOf<Any?>("requestTeacherRole"), functions.calls.single())
        functions.response = mapOf("status" to "nope")
        assertSame(LinkFailure.Unknown, repo.requestTeacherRole().exceptionOrNull())
        functions.failure = FunctionsCallException(
            "FAILED_PRECONDITION",
            mapOf("reason" to "NOT_ELIGIBLE", "deny" to "NOT_ADULT")
        )
        assertSame(LinkFailure.NotEligible, repo.requestTeacherRole().exceptionOrNull())
    }
}
