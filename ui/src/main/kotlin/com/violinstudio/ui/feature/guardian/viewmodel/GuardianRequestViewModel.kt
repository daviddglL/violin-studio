package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Consentimiento de un menor: pide al servidor que envíe el enlace al tutor. No navega: el servidor deja la cuenta en
 * `parental_pending` y la sesión sustituye la pantalla. La respuesta es genérica (no se muestra nada del tutor).
 */
@HiltViewModel
class GuardianRequestViewModel @Inject constructor(
    private val requestConsent: RequestGuardianConsentUseCase,
    private val getOwnEmail: GetOwnEmailUseCase,
    private val deleteAccount: DeleteAccountUseCase,
    private val signOut: SignOutUseCase,
    private val refreshTrigger: SessionRefreshTrigger
) : MviViewModel<GuardianRequestState, GuardianRequestIntent, UiEffect>(GuardianRequestState()) {
    private var submitPending = false
    private var deletePending = false
    private var signOutPending = false

    private val busy get() = submitPending || deletePending || signOutPending

    override fun onIntent(intent: GuardianRequestIntent) {
        when (intent) {
            GuardianRequestIntent.SubmitGuardianEmail -> {
                if (busy) return
                submitPending = true
            }
            GuardianRequestIntent.DeleteAccount -> {
                if (busy) return
                deletePending = true
            }
            GuardianRequestIntent.SignOut -> {
                if (busy) return
                signOutPending = true
            }
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: GuardianRequestIntent) = when (intent) {
        is GuardianRequestIntent.SessionUpdated -> reduce(GuardianRequestMutation.SessionUpdated(intent.reason))
        is GuardianRequestIntent.EmailChanged -> reduce(GuardianRequestMutation.EmailChanged(intent.email))
        GuardianRequestIntent.SubmitGuardianEmail -> onSubmit()
        GuardianRequestIntent.DeleteAccount -> onDelete()
        GuardianRequestIntent.SignOut -> onSignOut()
    }

    private suspend fun onSubmit() {
        try {
            reduce(GuardianRequestMutation.SubmitRequested)
            val current = state.value
            // Un formato inválido no pasa de aquí: ni siquiera sale del dispositivo.
            if (!current.isLoading) return
            if (isOwnEmail(current.email)) {
                reduce(GuardianRequestMutation.OwnEmailRejected)
                return
            }
            runCatchingNonCancellation { requestConsent(current.email) }.fold(
                onSuccess = { reduce(GuardianRequestMutation.Succeeded) },
                onFailure = { reduce(failureMutation(it)) }
            )
        } finally {
            submitPending = false
        }
    }

    private fun failureMutation(failure: Throwable): GuardianRequestMutation =
        when (val outcome = failure.toGuardianOutcome()) {
        GuardianOutcome.EmailRejected -> GuardianRequestMutation.EmailRejected
        GuardianOutcome.AlreadyApproved -> {
            // La sesión puede no haberlo visto aún: que se resuelva de nuevo.
            refreshTrigger.requestRefresh()
            GuardianRequestMutation.AlreadyApproved
        }
        is GuardianOutcome.Failed -> GuardianRequestMutation.Failed(outcome.error, outcome.retryAfterSeconds)
    }

    private suspend fun onDelete() {
        try {
            reduce(GuardianRequestMutation.DeleteStarted)
            // Éxito: el repositorio cierra la sesión y el host sustituye la pantalla; no se afirma nada más.
            runCatchingNonCancellation { deleteAccount() }.fold(
                onSuccess = { reduce(GuardianRequestMutation.DeleteSucceeded) },
                onFailure = { reduce(GuardianRequestMutation.DeleteFailed(it.toDeleteError())) }
            )
        } finally {
            deletePending = false
        }
    }

    private suspend fun onSignOut() {
        try {
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa; la sesión sigue siendo la fuente de verdad.
        } finally {
            signOutPending = false
        }
    }

    /** Comparación normalizada: sin mayúsculas ni espacios. Sin email propio conocido no se bloquea nada. */
    private suspend fun isOwnEmail(typed: String): Boolean {
        val own = runCatchingNonCancellation { Result.success(getOwnEmail()) }.getOrNull() ?: return false
        return own.trim().equals(typed.trim(), ignoreCase = true)
    }

    private fun reduce(mutation: GuardianRequestMutation) = setState { GuardianRequestReducer.reduce(this, mutation) }
}
