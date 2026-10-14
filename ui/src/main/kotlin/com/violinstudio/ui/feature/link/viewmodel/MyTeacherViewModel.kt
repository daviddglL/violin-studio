package com.violinstudio.ui.feature.link.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.usecase.ObserveMyTeacherUseCase
import com.violinstudio.domain.feature.link.usecase.UnlinkUseCase
import com.violinstudio.ui.commons.flow.SETTLE_MS as SETTLE_WINDOW_MS
import com.violinstudio.ui.commons.flow.collectSettled
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sin logs ni uid en errores (lección 8). El observador de dominio vacía la lista ante `permission-denied`, así que
 * un vínculo caído llega como `null`.
 */
@HiltViewModel
class MyTeacherViewModel @Inject constructor(
    private val observeTeacher: ObserveMyTeacherUseCase,
    private val unlink: UnlinkUseCase
) : MviViewModel<MyTeacherState, MyTeacherIntent, MyTeacherEffect>(MyTeacherState()) {
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
            // La primera instantánea vacía suele ser la caché: se espera a que llegue el servidor antes de afirmar
            // que no hay profesor.
            observeTeacher().collectSettled(isEmpty = { it == null }) { link ->
                setState { copy(isLoading = false, teacher = link) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa. Se deja de cargar y se ofrece reintentar; nunca se afirma "sin profesor".
            setState { copy(isLoading = false, loadFailed = true) }
        }
    }

    override fun onIntent(intent: MyTeacherIntent) {
        if (intent == MyTeacherIntent.ConfirmUnlink) {
            if (unlinking) return
            unlinking = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: MyTeacherIntent) = when (intent) {
        MyTeacherIntent.RequestUnlink -> setState {
            if (teacher == null || isUnlinking) this else copy(confirmingUnlink = true, error = null)
        }
        MyTeacherIntent.CancelUnlink -> setState { if (isUnlinking) this else copy(confirmingUnlink = false) }
        MyTeacherIntent.ConfirmUnlink -> confirmUnlink()
        MyTeacherIntent.Retry -> retry()
    }

    private fun retry() {
        if (!state.value.loadFailed) return
        setState { copy(isLoading = true, loadFailed = false) }
        startObserving()
    }

    private suspend fun confirmUnlink() {
        try {
            val link = state.value.takeIf { it.confirmingUnlink }?.teacher ?: return
            setState { copy(isUnlinking = true) }
            val result = try {
                unlink(link.teacherUid, link.studentUid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(LinkFailure.Unknown)
            }
            setState {
                copy(
                    isUnlinking = false,
                    confirmingUnlink = false,
                    error = result.exceptionOrNull()?.toMyTeacherError()
                )
            }
        } finally {
            unlinking = false
        }
    }

    companion object {
        const val SETTLE_MS = SETTLE_WINDOW_MS
    }
}

private fun Throwable.toMyTeacherError(): MyTeacherError = when (this) {
    LinkFailure.Offline -> MyTeacherError.Offline
    LinkFailure.TemporarilyUnavailable -> MyTeacherError.TemporarilyUnavailable
    else -> MyTeacherError.Unknown
}
