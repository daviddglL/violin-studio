package com.violinstudio.ui.feature.assignment.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.AssignmentStatus
import com.violinstudio.domain.feature.assignment.usecase.CompleteAssignmentUseCase
import com.violinstudio.domain.feature.assignment.usecase.ObserveMyAssignmentsUseCase
import com.violinstudio.domain.feature.assignment.usecase.ReopenAssignmentUseCase
import com.violinstudio.ui.commons.flow.collectSettled
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tareas del alumno: marcar hecha y reabrir. Offline-first: las escrituras no esperan al servidor, el SDK reemite
 * la tarea con `pendingSync`; si las reglas la rechazan después la tarea vuelve a su estado en silencio (la vista
 * lo tolera). El cliente nunca envía `doneAt`. Sin logs ni datos en errores (lección 8).
 */
@HiltViewModel
class StudentAssignmentsViewModel @Inject constructor(
    private val observeAssignments: ObserveMyAssignmentsUseCase,
    private val completeAssignment: CompleteAssignmentUseCase,
    private val reopenAssignment: ReopenAssignmentUseCase
) : MviViewModel<StudentAssignmentsState, StudentAssignmentsIntent, StudentAssignmentsEffect>(
    StudentAssignmentsState()
) {
    private var observer: Job? = null

    // Tareas con una escritura en cola o en curso; se rellena en onIntent (síncrono) para cortar el doble toque.
    private val inFlight = mutableSetOf<String>()

    init {
        startObserving()
    }

    private fun startObserving() {
        observer?.cancel()
        observer = viewModelScope.launch { observe() }
    }

    private suspend fun observe() {
        try {
            // La primera lista vacía puede ser solo la caché: se espera al servidor antes de mostrar el vacío.
            observeAssignments().collectSettled(isEmpty = { it.isEmpty() }) { list ->
                setState { copy(isLoading = false, assignments = list) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    override fun onIntent(intent: StudentAssignmentsIntent) {
        val id = when (intent) {
            is StudentAssignmentsIntent.Complete -> intent.id
            is StudentAssignmentsIntent.Reopen -> intent.id
            else -> null
        }
        if (id != null && !inFlight.add(id)) return
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: StudentAssignmentsIntent) = when (intent) {
        is StudentAssignmentsIntent.Complete -> write(intent.id, AssignmentStatus.OPEN, completeAssignment::invoke)
        is StudentAssignmentsIntent.Reopen -> write(intent.id, AssignmentStatus.DONE, reopenAssignment::invoke)
        StudentAssignmentsIntent.DismissError -> setState { copy(error = null) }
        StudentAssignmentsIntent.Retry -> retry()
    }

    private fun retry() {
        if (!state.value.loadFailed) return
        setState { copy(isLoading = true, loadFailed = false) }
        startObserving()
    }

    /** Solo escribe si la tarea existe y está en el estado de partida esperado ([from]). */
    private suspend fun write(id: String, from: AssignmentStatus, call: suspend (String) -> Result<Unit>) {
        try {
            if (state.value.assignments.none { it.id == id && it.status == from }) return
            setState { copy(busyIds = busyIds + id, error = null) }
            val result = try {
                call(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AssignmentFailure.Unknown)
            }
            val failure = result.exceptionOrNull()
            setState {
                copy(
                    busyIds = busyIds - id,
                    error = failure?.let {
                        if (it == AssignmentFailure.PermissionDenied) {
                            StudentAssignmentsError.PERMISSION_DENIED
                        } else {
                            StudentAssignmentsError.UNKNOWN
                        }
                    }
                )
            }
        } finally {
            inFlight -= id
            if (id in state.value.busyIds) setState { copy(busyIds = busyIds - id) }
        }
    }
}
