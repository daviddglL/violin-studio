package com.violinstudio.ui.feature.assignment.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.assignment.failure.AssignmentFailure
import com.violinstudio.domain.feature.assignment.model.Assignment
import com.violinstudio.domain.feature.assignment.usecase.DeleteAssignmentUseCase
import com.violinstudio.domain.feature.assignment.usecase.ObserveStudentAssignmentsUseCase
import com.violinstudio.ui.commons.flow.SETTLE_MS
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tareas del profesor para el alumno de la ruta (`studentUid` en `SavedStateHandle`). Solo se listan (y se pueden
 * borrar) las suyas: el caso de uso consulta por `teacherUid`. Si las reglas deniegan (vínculo caído) el caso de uso
 * emite vacío y termina: la vista se vacía y se vuelve atrás. Las escrituras son offline-first: una tarea pendiente
 * que desaparece sin que el profesor la haya borrado fue rechazada por las reglas y se avisa. Sin logs ni datos en
 * errores (lección 8).
 */
@HiltViewModel
class TeacherAssignmentsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeAssignments: ObserveStudentAssignmentsUseCase,
    private val deleteAssignment: DeleteAssignmentUseCase
) : MviViewModel<TeacherAssignmentsState, TeacherAssignmentsIntent, TeacherAssignmentsEffect>(
    TeacherAssignmentsState()
) {
    private val studentUid: String? = savedStateHandle.get<String>(STUDENT_UID_KEY)
    private var observer: Job? = null
    private var pendingEmpty: Job? = null
    private var shown: List<Assignment>? = null
    private var pendingIds: Set<String> = emptySet()
    private val locallyDeleted = mutableSetOf<String>()
    private var gone = false
    private var deleting = false

    init {
        start()
    }

    private fun start() {
        observer?.cancel()
        pendingEmpty?.cancel()
        shown = null
        pendingIds = emptySet()
        gone = false
        val uid = studentUid
        if (uid == null) {
            setState { copy(isLoading = false, unavailable = true) }
            return
        }
        observer = viewModelScope.launch { observe(uid) }
    }

    private suspend fun observe(uid: String) {
        try {
            // Un primer vacío (puede ser solo la caché) se retiene hasta el margen: si llega otra emisión o el flujo
            // termina (permission-denied) nunca se aplica.
            observeAssignments(uid).collect { onEmission(it) }
            pendingEmpty?.cancel()
            onLinkLost()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    private fun onEmission(list: List<Assignment>) {
        pendingEmpty?.cancel()
        if (list.isEmpty() && shown == null) {
            pendingEmpty = viewModelScope.launch {
                delay(SETTLE_MS)
                onList(list)
            }
        } else {
            onList(list)
        }
    }

    private fun onList(list: List<Assignment>) {
        if (gone) return
        val ids = list.mapTo(mutableSetOf()) { it.id }
        val rejected = pendingIds.any { it !in ids && it !in locallyDeleted }
        locallyDeleted.retainAll(ids)
        pendingIds = list.filter { it.pendingSync }.mapTo(mutableSetOf()) { it.id }
        shown = list
        setState {
            copy(
                isLoading = false,
                assignments = list,
                notice = if (rejected) TeacherAssignmentsNotice.REJECTED else notice
            )
        }
    }

    private fun onLinkLost() {
        if (gone) return
        gone = true
        setState {
            copy(
                isLoading = false,
                unavailable = true,
                assignments = emptyList(),
                deleteTarget = null,
                notice = null
            )
        }
        sendEffect(TeacherAssignmentsEffect.NavigateBack)
    }

    override fun onIntent(intent: TeacherAssignmentsIntent) {
        if (intent == TeacherAssignmentsIntent.ConfirmDelete) {
            if (deleting) return
            deleting = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: TeacherAssignmentsIntent) = when (intent) {
        is TeacherAssignmentsIntent.RequestDelete -> setState {
            val target = assignments.firstOrNull { it.id == intent.id }
            if (isDeleting || target == null) this else copy(deleteTarget = target, error = null)
        }
        TeacherAssignmentsIntent.CancelDelete -> setState { if (isDeleting) this else copy(deleteTarget = null) }
        TeacherAssignmentsIntent.ConfirmDelete -> confirmDelete()
        TeacherAssignmentsIntent.DismissError -> setState { copy(error = null) }
        TeacherAssignmentsIntent.DismissNotice -> setState { copy(notice = null) }
        TeacherAssignmentsIntent.Retry -> retry()
    }

    private fun retry() {
        if (!state.value.loadFailed) return
        setState { copy(isLoading = true, loadFailed = false) }
        start()
    }

    private suspend fun confirmDelete() {
        try {
            val target = state.value.deleteTarget ?: return
            val uid = studentUid ?: return
            setState { copy(isDeleting = true) }
            val result = try {
                deleteAssignment(uid, target.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AssignmentFailure.Unknown)
            }
            if (result.isSuccess) locallyDeleted += target.id
            val failure = result.exceptionOrNull()
            setState {
                copy(
                    isDeleting = false,
                    deleteTarget = null,
                    error = failure?.let {
                        if (it == AssignmentFailure.PermissionDenied) {
                            TeacherAssignmentsError.PERMISSION_DENIED
                        } else {
                            TeacherAssignmentsError.UNKNOWN
                        }
                    }
                )
            }
        } finally {
            deleting = false
        }
    }

    companion object {
        /** Nombre del argumento de la ruta: `SavedStateHandle` lo recibe por nombre de propiedad. */
        const val STUDENT_UID_KEY = "studentUid"
    }
}
