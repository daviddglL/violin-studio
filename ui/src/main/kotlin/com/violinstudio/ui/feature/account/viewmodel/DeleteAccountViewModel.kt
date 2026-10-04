package com.violinstudio.ui.feature.account.viewmodel

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.usecase.GetReauthMethodUseCase
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.domain.feature.auth.usecase.ReauthenticateUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Borrado de cuenta compartido (D1): lo usan todas las pantallas de sesion. Confirmar llama a `deleteAccount`; solo si
 * el servidor exige un login reciente se pide la reautenticacion (contrasena o Google) y entonces se reintenta el
 * borrado. Una reautenticacion fallida nunca llama a `deleteAccount`. Ningun fallo afirma que la cuenta se borro: el
 * exito solo bloquea la pantalla hasta que la sesion pasa a `LoggedOut`.
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val deleteAccount: DeleteAccountUseCase,
    private val reauthenticate: ReauthenticateUseCase,
    private val getReauthMethod: GetReauthMethodUseCase
) : MviViewModel<DeleteAccountState, DeleteAccountIntent, UiEffect>(DeleteAccountState()) {
    private var workPending = false

    /** El flag se activa al encolar (los intents se procesan de uno en uno) para descartar lo que llega despues. */
    override fun onIntent(intent: DeleteAccountIntent) {
        when (intent) {
            DeleteAccountIntent.Confirm, DeleteAccountIntent.SubmitPassword, is DeleteAccountIntent.GoogleToken -> {
                // Un envio que el estado ya no admite no ocupa el flag: lo que se escriba despues no se pierde.
                if (workPending || !state.value.admits(intent)) return
                workPending = true
            }
            DeleteAccountIntent.Cancel, is DeleteAccountIntent.PasswordChanged, DeleteAccountIntent.GoogleFailed ->
                if (workPending) return
            DeleteAccountIntent.Open -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: DeleteAccountIntent) = when (intent) {
        DeleteAccountIntent.Open -> reduce(DeleteAccountMutation.Opened)
        DeleteAccountIntent.Cancel -> reduce(DeleteAccountMutation.Cancelled)
        is DeleteAccountIntent.PasswordChanged -> reduce(DeleteAccountMutation.PasswordChanged(intent.value))
        DeleteAccountIntent.GoogleFailed ->
            reduce(DeleteAccountMutation.ReauthFailed(DeleteAccountError.PROVIDER_UNAVAILABLE))
        DeleteAccountIntent.Confirm -> work(DeleteAccountMutation.DeleteStarted) { delete() }
        DeleteAccountIntent.SubmitPassword -> {
            val state = state.value
            work(DeleteAccountMutation.ReauthStarted, enabled = state.canSubmitPassword) {
                reauthThenDelete { reauthenticate(state.password) }
            }
        }
        is DeleteAccountIntent.GoogleToken ->
            work(DeleteAccountMutation.ReauthStarted, enabled = state.value.canReauthWithGoogle) {
                reauthThenDelete { reauthenticate(intent.token) }
            }
    }

    private fun DeleteAccountState.admits(intent: DeleteAccountIntent) = when (intent) {
        DeleteAccountIntent.Confirm -> canConfirm
        DeleteAccountIntent.SubmitPassword -> canSubmitPassword
        is DeleteAccountIntent.GoogleToken -> canReauthWithGoogle
        else -> true
    }

    /** [enabled] recoge lo que el reducer rechazaria de todos modos: sin carga no hay nada que ejecutar. */
    private suspend fun work(start: DeleteAccountMutation, enabled: Boolean = true, block: suspend () -> Unit) {
        try {
            if (!enabled) return
            reduce(start)
            if (!state.value.isWorking) return
            block()
        } finally {
            workPending = false
        }
    }

    private suspend fun reauthThenDelete(reauth: suspend () -> Result<Unit>) {
        runNonCancelling { reauth() }.fold(
            onSuccess = { delete() },
            onFailure = { reduce(DeleteAccountMutation.ReauthFailed(it.toReauthError())) }
        )
    }

    private suspend fun delete() {
        runNonCancelling { deleteAccount() }.fold(
            onSuccess = { reduce(DeleteAccountMutation.Deleted) },
            onFailure = {
                if (it == AccountFailure.RequiresRecentLogin) {
                    onReauthRequired()
                } else {
                    reduce(DeleteAccountMutation.DeleteFailed(it.toDeleteError()))
                }
            }
        )
    }

    private suspend fun onReauthRequired() {
        val method = runNonCancelling { Result.success(getReauthMethod()) }.getOrDefault(ReauthMethod.NONE)
        reduce(
            if (method == ReauthMethod.NONE) {
                DeleteAccountMutation.DeleteFailed(DeleteAccountError.REAUTH_UNAVAILABLE)
            } else {
                DeleteAccountMutation.ReauthRequired(method)
            }
        )
    }

    private fun Throwable.toDeleteError() = when (this) {
        AccountFailure.Network -> DeleteAccountError.NETWORK
        else -> DeleteAccountError.FAILED
    }

    private fun Throwable.toReauthError() = when (this) {
        AuthFailure.InvalidCredentials -> DeleteAccountError.WRONG_PASSWORD
        AuthFailure.TooManyRequests -> DeleteAccountError.TOO_MANY_ATTEMPTS
        AuthFailure.Network -> DeleteAccountError.NETWORK
        AuthFailure.ProviderUnavailable -> DeleteAccountError.PROVIDER_UNAVAILABLE
        else -> DeleteAccountError.FAILED
    }

    private suspend fun <T> runNonCancelling(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Result.failure(IllegalStateException("account operation failed"))
    }

    private fun reduce(mutation: DeleteAccountMutation) = setState { DeleteAccountReducer.reduce(this, mutation) }
}
