package com.violinstudio.domain.feature.link.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.model.TeacherCode
import com.violinstudio.domain.feature.link.model.TeacherLink
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeLinkRepository
import com.violinstudio.domain.testing.FakePracticeLogRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkUseCasesTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakeLinkRepository()
    private val me = verifiedUser.uid

    private fun link(t: String, s: String, at: Long = 1) = TeacherLink(
        t,
        s,
        "Profe $t",
        "Alumno $s",
        Instrument.VIOLIN,
        2,
        Instant.ofEpochSecond(at)
    )

    @Test
    fun `ObserveMyTeacher emite el vinculo mas antiguo del alumno o null`() = runTest {
        val observe = ObserveMyTeacherUseCase(auth, repo)
        assertNull(observe().first())
        repo.links.value = listOf(link("t2", me, at = 20), link("t1", me, at = 10), link("t3", "otro"))
        assertEquals("t1", observe().first()?.teacherUid)
        assertTrue("observeAsStudent:$me" in repo.calls)
    }

    @Test
    fun `sin sesion los observadores emiten vacio sin tocar el repositorio`() = runTest {
        auth.user = null
        assertNull(ObserveMyTeacherUseCase(auth, repo)().first())
        assertEquals(emptyList<TeacherLink>(), ObserveMyStudentsUseCase(auth, repo)().first())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `ObserveMyStudents ordena por nombre sin distinguir mayusculas y PermissionDenied vacia la lista`() = runTest {
        val observe = ObserveMyStudentsUseCase(auth, repo)
        repo.links.value = listOf(
            link(me, "s1").copy(studentDisplayName = "zoe"),
            link(me, "s2").copy(studentDisplayName = "Ana")
        )
        assertEquals(listOf("Ana", "zoe"), observe().first().map { it.studentDisplayName })
        repo.observeFailure = LinkFailure.PermissionDenied
        assertEquals(emptyList<TeacherLink>(), observe().first())
        repo.observeFailure = LinkFailure.Offline
        assertEquals(LinkFailure.Offline, runCatching { observe().toList() }.exceptionOrNull())
    }

    @Test
    fun `Unlink delega, refresca claims y solo lo puede pedir una de las partes`() = runTest {
        val unlink = UnlinkUseCase(auth, repo)
        assertEquals(Result.success(Unit), unlink("t1", me))
        assertEquals(listOf("unlink:t1:$me"), repo.calls)
        assertEquals(listOf("claims:true"), auth.calls)
        assertEquals(LinkFailure.PermissionDenied, unlink("t1", "otro").exceptionOrNull())
        assertEquals(1, repo.calls.size)
        auth.user = null
        assertEquals(LinkFailure.NoSession, unlink("t1", me).exceptionOrNull())
    }

    @Test
    fun `Unlink propaga el fallo sin refrescar y el refresco fallido no lo invalida`() = runTest {
        repo.unlinkResult = Result.failure(LinkFailure.Offline)
        assertEquals(LinkFailure.Offline, UnlinkUseCase(auth, repo)("t1", me).exceptionOrNull())
        assertTrue(auth.calls.isEmpty())
        repo.unlinkResult = Result.success(Unit)
        auth.claimsResults = mutableListOf(Result.failure(AuthFailure.Network))
        assertEquals(Result.success(Unit), UnlinkUseCase(auth, repo)("t1", me))
    }

    @Test
    fun `los codigos se crean, listan y revocan por delegacion`() = runTest {
        val code = TeacherCode("id1", "ABCD2345", "45", Instant.ofEpochSecond(5))
        repo.createResult = Result.success(code)
        repo.listResult = Result.success(listOf(code.copy(code = null)))
        assertEquals(Result.success(code), CreateTeacherCodeUseCase(repo)())
        assertNull(ListTeacherCodesUseCase(repo)().getOrThrow().single().code)
        repo.revokeResult = Result.failure(LinkFailure.InvalidCode)
        assertEquals(LinkFailure.InvalidCode, RevokeTeacherCodeUseCase(repo)("id1").exceptionOrNull())
        assertEquals(listOf("createCode", "listCodes", "revokeCode:id1"), repo.calls)
    }

    @Test
    fun `ObserveStudentPractice usa el uid del alumno, ordena desc y limita a 200`() = runTest {
        val practice = FakePracticeLogRepository()
        practice.seed(
            "s1",
            PracticeSession("a", Instant.parse("2026-03-01T10:00:00Z"), 60, Instrument.VIOLIN, null, false),
            PracticeSession("b", Instant.parse("2026-03-02T10:00:00Z"), 60, Instrument.VIOLIN, null, false)
        )
        assertEquals(listOf("b", "a"), ObserveStudentPracticeUseCase(practice)("s1").first().map { it.id })
        assertEquals(200, practice.lastLimit)
        assertEquals(listOf("observe:s1"), practice.calls)
    }

    @Test
    fun `cancelar el colector libera la suscripcion al repositorio`() = runTest {
        val job = launch(start = CoroutineStart.UNDISPATCHED) { ObserveMyStudentsUseCase(auth, repo)().collect { } }
        runCurrent()
        assertEquals(1, repo.links.subscriptionCount.value)
        job.cancel()
        job.join()
        runCurrent()
        assertEquals(0, repo.links.subscriptionCount.value)
    }
}
