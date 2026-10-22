package com.violinstudio.ui.feature.link.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.link.failure.LinkFailure
import com.violinstudio.domain.feature.link.usecase.RequestTeacherRoleUseCase
import com.violinstudio.domain.feature.profile.usecase.ObserveProfileUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Solicitud de alta de profesor. El estado sale del perfil (`users/{uid}.teacherRequest`), asi que una aprobacion o
 * un rechazo del administrador se ven sin recargar. Sin logs: nada de la cuenta sale de aqui.
 */
@HiltViewModel
class TeacherRequestViewModel @Inject constructor(
    observeProfile: ObserveProfileUseCase,
    private val request: RequestTeacherRoleUseCase
) : MviViewModel<TeacherRequestState, TeacherRequestIntent, TeacherRequestEffect>(TeacherRequestState()) {
    private var sending = false

    init {
        viewModelScope.launch {
            try {
                observeProfile().collect { profile ->
                    profile?.teacherRequest?.let { status -> setState { copy(status = status) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Sin perfil legible se sigue ofreciendo enviar: el servidor decide.
            }
        }
    }

    override fun onIntent(intent: TeacherRequestIntent) {
        if (intent == TeacherRequestIntent.Submit) {
            if (sending) return
            sending = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: TeacherRequestIntent) = when (intent) {
        TeacherRequestIntent.Submit -> submit()
    }

    private suspend fun submit() {
        try {
            if (!state.value.canSubmit) return
            setState { copy(isSending = true, error = null) }
            val result = try {
                request()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(LinkFailure.Unknown)
            }
            result.fold(
                onSuccess = { status -> setState { copy(isSending = false, status = status) } },
                onFailure = { failure -> setState { copy(isSending = false, error = failure.toRequestError()) } }
            )
        } finally {
            sending = false
        }
    }
}

private fun Throwable.toRequestError(): TeacherRequestError = when (this) {
    LinkFailure.NotEligible, LinkFailure.NoActiveConsent -> TeacherRequestError.NotEligible
    LinkFailure.Offline -> TeacherRequestError.Offline
    else -> TeacherRequestError.Unknown
}
