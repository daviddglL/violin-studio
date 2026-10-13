package com.violinstudio.ui.feature.link.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.usecase.ObserveMyTeacherUseCase
import com.violinstudio.domain.feature.link.usecase.UnlinkUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

    init {
        viewModelScope.launch { observe() }
    }

    private suspend fun observe() {
        var seen = false
        try {
            observeTeacher().collectLatest { link ->
                // La primera instantánea vacía suele ser la caché: se espera un momento a que llegue el servidor
                // (una emisión nueva cancela esta espera) antes de afirmar que no hay profesor.
                if (link == null && !seen) delay(SETTLE_MS)
                seen = true
                setState { copy(isLoading = false, teacher = link) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa. Se deja de cargar para no quedar en un spinner eterno.
            setState { copy(isLoading = false) }
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
        const val SETTLE_MS = 1_500L
    }
}

private fun Throwable.toMyTeacherError(): MyTeacherError = when (this) {
    LinkFailure.Offline -> MyTeacherError.Offline
    LinkFailure.TemporarilyUnavailable -> MyTeacherError.TemporarilyUnavailable
    else -> MyTeacherError.Unknown
}
