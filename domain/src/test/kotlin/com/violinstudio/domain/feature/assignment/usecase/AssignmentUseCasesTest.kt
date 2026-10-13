package com.violinstudio.domain.feature.assignment.usecase

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeAssignmentRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssignmentUseCasesTest {
    private val auth = FakeAuthRepository().apply { user = verifiedUser }
    private val repo = FakeAssignmentRepository()
    private val me = verifiedUser.uid

    private fun task(id: String, teacher: String, status: AssignmentStatus = AssignmentStatus.OPEN, due: Long? = null) =
        Assignment(id, teacher, "t$id", null, due?.let(Instant::ofEpochSecond), status, null, Instant.EPOCH, false)

    @Test
    fun `crear genera el id en cliente, valida y escribe con el uid del profesor`() = runTest {
        val create = CreateAssignmentUseCase(auth, repo) { "gen-id" }
        assertEquals(Result.success("gen-id"), create("s1", " Escala ", null, null))
        assertEquals(listOf("create:s1:$me:gen-id:Escala"), repo.calls)
    }

    @Test
    fun `crear con datos invalidos o sin sesion no escribe`() = runTest {
        val create = CreateAssignmentUseCase(auth, repo) { "gen-id" }
        assertEquals(AssignmentFailure.TitleTooLong, create("s1", "a".repeat(121), null, null).exceptionOrNull())
        assertEquals(AssignmentFailure.DescriptionTooLong, create("s1", "a", "d".repeat(1001), null).exceptionOrNull())
        auth.user = null
        assertEquals(AssignmentFailure.NoSession, create("s1", "a", null, null).exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `editar valida y delega, y propaga el rechazo`() = runTest {
        val update = UpdateAssignmentUseCase(auth, repo)
        assertEquals(Result.success(Unit), update("s1", "a1", "Nuevo", "desc", Instant.ofEpochSecond(9)))
        assertEquals(listOf("update:s1:a1:Nuevo"), repo.calls)
        assertEquals(AssignmentFailure.TitleEmpty, update("s1", "a1", " ", null, null).exceptionOrNull())
        repo.writeFailure = AssignmentFailure.PermissionDenied
        assertEquals(AssignmentFailure.PermissionDenied, update("s1", "a1", "x", null, null).exceptionOrNull())
        auth.user = null
        assertEquals(AssignmentFailure.NoSession, update("s1", "a1", "x", null, null).exceptionOrNull())
    }

    @Test
    fun `completar y reabrir actuan sobre las tareas del propio alumno`() = runTest {
        assertEquals(Result.success(Unit), CompleteAssignmentUseCase(auth, repo)("a1"))
        assertEquals(Result.success(Unit), ReopenAssignmentUseCase(auth, repo)("a1"))
        assertEquals(listOf("setDone:$me:a1:true", "setDone:$me:a1:false"), repo.calls)
        auth.user = null
        assertEquals(AssignmentFailure.NoSession, CompleteAssignmentUseCase(auth, repo)("a1").exceptionOrNull())
        assertEquals(AssignmentFailure.NoSession, ReopenAssignmentUseCase(auth, repo)("a1").exceptionOrNull())
    }

    @Test
    fun `borrar delega con el uid del alumno dado`() = runTest {
        assertEquals(Result.success(Unit), DeleteAssignmentUseCase(auth, repo)("s1", "a1"))
        assertEquals(listOf("delete:s1:a1"), repo.calls)
        auth.user = null
        assertEquals(AssignmentFailure.NoSession, DeleteAssignmentUseCase(auth, repo)("s1", "a1").exceptionOrNull())
    }

    @Test
    fun `el alumno ve sus tareas ordenadas y vacio sin sesion`() = runTest {
        repo.seed(
            me,
            task("late", "t", due = 200),
            task("done", "t", AssignmentStatus.DONE),
            task("soon", "t", due = 100)
        )
        assertEquals(listOf("soon", "late", "done"), ObserveMyAssignmentsUseCase(auth, repo)().first().map { it.id })
        auth.user = null
        assertEquals(emptyList<Assignment>(), ObserveMyAssignmentsUseCase(auth, repo)().first())
    }

    @Test
    fun `el profesor observa solo las que creo para ese alumno y PermissionDenied vacia`() = runTest {
        repo.seed("s1", task("mine", me, due = 5), task("other", "otro-profe"))
        val observe = ObserveStudentAssignmentsUseCase(auth, repo)
        assertEquals(listOf("mine"), observe("s1").first().map { it.id })
        assertEquals("observeByTeacher:$me:s1", repo.calls.last())
        repo.observeFailure = AssignmentFailure.PermissionDenied
        assertEquals(emptyList<Assignment>(), observe("s1").first())
        repo.observeFailure = AssignmentFailure.Unknown
        assertEquals(AssignmentFailure.Unknown, runCatching { observe("s1").toList() }.exceptionOrNull())
        auth.user = null
        assertEquals(emptyList<Assignment>(), observe("s1").first())
    }

    @Test
    fun `ids con caracteres no seguros se rechazan en editar, completar, reabrir y borrar`() = runTest {
        val bad = "a/b"
        val update = UpdateAssignmentUseCase(auth, repo)("s1", bad, "x", null, null)
        assertEquals(AssignmentFailure.InvalidId, update.exceptionOrNull())
        assertEquals(AssignmentFailure.InvalidId, CompleteAssignmentUseCase(auth, repo)(bad).exceptionOrNull())
        assertEquals(AssignmentFailure.InvalidId, ReopenAssignmentUseCase(auth, repo)(bad).exceptionOrNull())
        assertEquals(AssignmentFailure.InvalidId, DeleteAssignmentUseCase(auth, repo)("s1", bad).exceptionOrNull())
        assertTrue(repo.calls.isEmpty())
    }
}
