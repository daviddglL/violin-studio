package com.violinstudio.domain.feature.assignment.usecase

import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.model.AssignmentRules
import com.violinstudio.domain.feature.assignment.repository.AssignmentRepository
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private suspend fun AuthRepository.uidOrNull(): String? = authUser.first()?.uid

/** El profesor crea una tarea para [studentUid]; devuelve el id generado en cliente (UUID). */
class CreateAssignmentUseCase(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository,
    private val newId: () -> String
) {
    @Inject
    constructor(auth: AuthRepository, repo: AssignmentRepository) : this(auth, repo, { UUID.randomUUID().toString() })

    suspend operator fun invoke(
        studentUid: String,
        title: String,
        description: String?,
        dueAt: Instant?
    ): Result<String> {
        val teacherUid = auth.uidOrNull() ?: return Result.failure(AssignmentFailure.NoSession)
        val draft = AssignmentDraft.create(newId(), title, description, dueAt).getOrElse { return Result.failure(it) }
        return repo.create(studentUid, teacherUid, draft).map { draft.id }
    }
}

class UpdateAssignmentUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository
) {
    suspend operator fun invoke(
        studentUid: String,
        id: String,
        title: String,
        description: String?,
        dueAt: Instant?
    ): Result<Unit> {
        auth.uidOrNull() ?: return Result.failure(AssignmentFailure.NoSession)
        val draft = AssignmentDraft.create(id, title, description, dueAt).getOrElse { return Result.failure(it) }
        return repo.update(studentUid, draft)
    }
}

/** El alumno marca como hecha una tarea suya. */
class CompleteAssignmentUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> {
        val uid = auth.uidOrNull() ?: return Result.failure(AssignmentFailure.NoSession)
        return repo.setDone(uid, id, done = true)
    }
}

/** El alumno reabre una tarea suya. */
class ReopenAssignmentUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> {
        val uid = auth.uidOrNull() ?: return Result.failure(AssignmentFailure.NoSession)
        return repo.setDone(uid, id, done = false)
    }
}

/** El profesor borra una tarea que creó (las reglas lo comprueban). */
class DeleteAssignmentUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository
) {
    suspend operator fun invoke(studentUid: String, id: String): Result<Unit> {
        auth.uidOrNull() ?: return Result.failure(AssignmentFailure.NoSession)
        return repo.delete(studentUid, id)
    }
}

/** Tareas del alumno con sesión en el orden de REQ-ASG-06; vacío sin sesión. */
class ObserveMyAssignmentsUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<Assignment>> = auth.authUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else {
            repo.observeForStudent(user.uid).map(AssignmentRules::sortForStudent)
        }
    }
}

/** Tareas que el profesor con sesión creó para [studentUid]; `PermissionDenied` (vínculo caído) vacía la lista. */
class ObserveStudentAssignmentsUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val repo: AssignmentRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(studentUid: String): Flow<List<Assignment>> = auth.authUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(emptyList())
        } else {
            repo.observeByTeacher(user.uid, studentUid)
                .map(AssignmentRules::sortForStudent)
                .catch { if (it is AssignmentFailure.PermissionDenied) emit(emptyList()) else throw it }
        }
    }
}
