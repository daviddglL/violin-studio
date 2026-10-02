package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.usecase.SendPasswordResetUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * La confirmación es siempre la misma ("si existe, recibirás un correo"): el caso de uso oculta `UserNotFound`. Un
 * email mal formado no llega a Firebase.
 */
@HiltViewModel
class ResetPasswordViewModel @Inject constructor(
    private val sendPasswordReset: SendPasswordResetUseCase
) : MviViewModel<ResetPasswordState, ResetPasswordIntent, ResetPasswordEffect>(ResetPasswordState()) {
    private var submitPending = false

    override fun onIntent(intent: ResetPasswordIntent) {
        if (intent == ResetPasswordIntent.Submit) {
            if (submitPending) return
            submitPending = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: ResetPasswordIntent) = when (intent) {
        is ResetPasswordIntent.EmailChanged -> reduce(ResetPasswordMutation.EmailChanged(intent.value))
        ResetPasswordIntent.Submit -> onSubmit()
        ResetPasswordIntent.ScreenLeft -> reduce(ResetPasswordMutation.ScreenLeft)
    }

    private suspend fun onSubmit() {
        try {
            reduce(ResetPasswordMutation.SubmitRequested)
            if (!state.value.isLoading) return
            val result = try {
                sendPasswordReset(state.value.email)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AuthFailure.Unknown())
            }
            result.fold(
                onSuccess = { reduce(ResetPasswordMutation.Sent) },
                onFailure = { reduce(it.toMutation()) }
            )
        } finally {
            submitPending = false
        }
    }

    private fun Throwable.toMutation(): ResetPasswordMutation = when (this) {
        AuthFailure.InvalidEmail -> ResetPasswordMutation.EmailRejected
        AuthFailure.TooManyRequests -> ResetPasswordMutation.Failed(ResetError.TOO_MANY_REQUESTS)
        AuthFailure.Network -> ResetPasswordMutation.Failed(ResetError.NETWORK)
        else -> ResetPasswordMutation.Failed(ResetError.UNKNOWN)
    }

    private fun reduce(mutation: ResetPasswordMutation) = setState { ResetPasswordReducer.reduce(this, mutation) }
}
