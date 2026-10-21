package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.model.AssignmentDraft
import com.violinstudio.domain.feature.assignment.repository.AssignmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Tareas en memoria por alumno; `calls` registra cada interaccion. */
class FakeAssignmentRepository : AssignmentRepository {
    private val store = MutableStateFlow<Map<String, List<Assignment>>>(emptyMap())
    val calls = mutableListOf<String>()

    /** Si no es `null`, las escrituras fallan con esto; los observadores, al recoger. */
    var writeFailure: AssignmentFailure? = null
    var observeFailure: AssignmentFailure? = null

    fun seed(studentUid: String, vararg assignments: Assignment) {
        store.value = store.value + (studentUid to assignments.toList())
    }

    override fun observeForStudent(studentUid: String): Flow<List<Assignment>> {
        calls += "observeForStudent:$studentUid"
        return observe(studentUid) { true }
    }

    override fun observeByTeacher(teacherUid: String, studentUid: String): Flow<List<Assignment>> {
        calls += "observeByTeacher:$teacherUid:$studentUid"
        return observe(studentUid) { it.teacherUid == teacherUid }
    }

    private fun observe(studentUid: String, filter: (Assignment) -> Boolean): Flow<List<Assignment>> = flow {
        observeFailure?.let { throw it }
        emitAll(store.map { it[studentUid].orEmpty().filter(filter) })
    }

    override suspend fun create(studentUid: String, teacherUid: String, draft: AssignmentDraft): Result<Unit> {
        calls += "create:$studentUid:$teacherUid:${draft.id}:${draft.title}"
        return result()
    }

    override suspend fun update(studentUid: String, draft: AssignmentDraft): Result<Unit> {
        calls += "update:$studentUid:${draft.id}:${draft.title}"
        return result()
    }

    override suspend fun setDone(studentUid: String, id: String, done: Boolean): Result<Unit> {
        calls += "setDone:$studentUid:$id:$done"
        return result()
    }

    override suspend fun delete(studentUid: String, id: String): Result<Unit> {
        calls += "delete:$studentUid:$id"
        return result()
    }

    private fun result(): Result<Unit> = writeFailure?.let { Result.failure(it) } ?: Result.success(Unit)
}
