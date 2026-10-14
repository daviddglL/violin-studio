package com.violinstudio.ui.feature.assignment.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.usecase.CreateAssignmentUseCase
import com.violinstudio.domain.feature.assignment.usecase.ObserveStudentAssignmentsUseCase
import com.violinstudio.domain.feature.assignment.usecase.UpdateAssignmentUseCase
import com.violinstudio.ui.commons.flow.collectSettled
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Crea (sin `assignmentId`) o edita (con él) una tarea del alumno de la ruta. Al editar, se rellena una sola vez con
 * la primera lectura de la tarea: las emisiones posteriores no pisan lo que se está escribiendo. Guardar es
 * offline-first (no espera al servidor). Sin logs ni contenido en errores (lección 8).
 */
@HiltViewModel
class AssignmentEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val createAssignment: CreateAssignmentUseCase,
    private val updateAssignment: UpdateAssignmentUseCase,
    private val observeAssignments: ObserveStudentAssignmentsUseCase
) : MviViewModel<AssignmentEditState, AssignmentEditIntent, AssignmentEditEffect>(
    AssignmentEditState(loading = savedStateHandle.get<String>(ASSIGNMENT_ID_KEY) != null)
) {
    private val studentUid: String? = savedStateHandle.get<String>(STUDENT_UID_KEY)
    private val assignmentId: String? = savedStateHandle.get<String>(ASSIGNMENT_ID_KEY)
    private var saving = false
    private var prefilled = false

    init {
        if (assignmentId != null) {
            val uid = studentUid
            if (uid == null) {
                reduce(AssignmentEditMutation.NotFound)
            } else {
                viewModelScope.launch { load(uid, assignmentId) }
            }
        }
    }

    private fun reduce(mutation: AssignmentEditMutation) = setState { AssignmentEditReducer.reduce(this, mutation) }

    private suspend fun load(uid: String, id: String) {
        try {
            observeAssignments(uid).collectSettled(isEmpty = { list -> list.none { it.id == id } }) { list ->
                val found = list.firstOrNull { it.id == id }
                if (found != null) prefill(found) else notFoundIfLoading()
            }
            notFoundIfLoading()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            notFoundIfLoading()
        }
    }

    private fun prefill(assignment: Assignment) {
        if (prefilled) return
        prefilled = true
        reduce(AssignmentEditMutation.Loaded(assignment.title, assignment.description, assignment.dueAt))
    }

    private fun notFoundIfLoading() {
        if (!prefilled) reduce(AssignmentEditMutation.NotFound)
    }

    override fun onIntent(intent: AssignmentEditIntent) {
        if (intent == AssignmentEditIntent.Save) {
            if (saving) return
            saving = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: AssignmentEditIntent) = when (intent) {
        is AssignmentEditIntent.TitleChanged -> reduce(AssignmentEditMutation.TitleEdited(intent.title))
        is AssignmentEditIntent.DescriptionChanged ->
            reduce(AssignmentEditMutation.DescriptionEdited(intent.description))
        is AssignmentEditIntent.DueAtChanged -> reduce(AssignmentEditMutation.DueAtChanged(intent.dueAt))
        AssignmentEditIntent.DismissError -> reduce(AssignmentEditMutation.ErrorDismissed)
        AssignmentEditIntent.Save -> save()
    }

    private suspend fun save() {
        var succeeded = false
        try {
            reduce(AssignmentEditMutation.SaveAttempted)
            val uid = studentUid
            val current = state.value
            if (uid == null || !current.canSave) return
            reduce(AssignmentEditMutation.Busy(true))
            val result = try {
                if (assignmentId == null) {
                    createAssignment(uid, current.title, current.description, current.dueAt).map { }
                } else {
                    updateAssignment(uid, assignmentId, current.title, current.description, current.dueAt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AssignmentFailure.Unknown)
            }
            val failure = result.exceptionOrNull()
            if (failure == null) {
                // Se queda ocupado hasta que la pantalla se cierra: evita un segundo guardado.
                succeeded = true
                sendEffect(AssignmentEditEffect.Saved)
            } else {
                val error = if (failure == AssignmentFailure.PermissionDenied) {
                    AssignmentEditError.PERMISSION_DENIED
                } else {
                    AssignmentEditError.UNKNOWN
                }
                reduce(AssignmentEditMutation.Failed(error))
            }
        } finally {
            if (!succeeded) saving = false
        }
    }

    companion object {
        const val STUDENT_UID_KEY = "studentUid"
        const val ASSIGNMENT_ID_KEY = "assignmentId"
    }
}
