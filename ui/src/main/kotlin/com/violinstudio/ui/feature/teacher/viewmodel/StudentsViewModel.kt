package com.violinstudio.ui.feature.teacher.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.usecase.ObserveMyStudentsUseCase
import com.violinstudio.domain.feature.link.usecase.UnlinkUseCase
import com.violinstudio.ui.commons.flow.collectSettled
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sin logs ni nombres/uid en errores (lección 8). El rol lo comprueba la navegación. El observador de dominio ya
 * vacía la lista ante `permission-denied`; aquí también se tolera por si el flujo lo lanza.
 */
@HiltViewModel
class StudentsViewModel @Inject constructor(
    private val observeStudents: ObserveMyStudentsUseCase,
    private val unlink: UnlinkUseCase
) : MviViewModel<StudentsState, StudentsIntent, StudentsEffect>(StudentsState()) {
    private var unlinking = false
    private var observer: Job? = null

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
            observeStudents().collectSettled(isEmpty = { it.isEmpty() }) { list ->
                setState { copy(isLoading = false, students = list) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: LinkFailure.PermissionDenied) {
            setState { copy(isLoading = false, students = emptyList()) }
        } catch (_: Throwable) {
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    override fun onIntent(intent: StudentsIntent) {
        if (intent == StudentsIntent.ConfirmUnlink) {
            if (unlinking) return
            unlinking = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: StudentsIntent) = when (intent) {
        is StudentsIntent.RequestUnlink -> setState {
            if (isUnlinking) this else copy(unlinkTarget = intent.link, error = null)
        }

        StudentsIntent.CancelUnlink -> setState { if (isUnlinking) this else copy(unlinkTarget = null) }

        StudentsIntent.ConfirmUnlink -> confirmUnlink()

        StudentsIntent.Retry -> retry()
    }

    private fun retry() {
        if (!state.value.loadFailed) return
        setState { copy(isLoading = true, loadFailed = false) }
        startObserving()
    }

    private suspend fun confirmUnlink() {
        try {
            val link = state.value.unlinkTarget ?: return
            setState { copy(isUnlinking = true) }
            val result = try {
                unlink(link.teacherUid, link.studentUid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(LinkFailure.Unknown)
            }
            setState {
                copy(isUnlinking = false, unlinkTarget = null, error = result.exceptionOrNull()?.toStudentsError())
            }
        } finally {
            unlinking = false
        }
    }
}

private fun Throwable.toStudentsError(): StudentsError = when (this) {
    LinkFailure.Offline -> StudentsError.Offline
    LinkFailure.TemporarilyUnavailable -> StudentsError.TemporarilyUnavailable
    else -> StudentsError.Unknown
}
