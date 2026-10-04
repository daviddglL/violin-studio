package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Espera mientras el tutor confirma. Solo hay reenviar (a la dirección que la app recuerda en memoria), cambiar el
 * email, borrar la cuenta y cerrar sesión. Reenviar y cambiar pasan por el mismo caso de uso: que el servidor invalide
 * el enlace anterior y cuente los límites es cosa suya. Nunca se registra ni se muestra el email del tutor.
 */
@HiltViewModel
class GuardianWaitViewModel @Inject constructor(
    private val requestConsent: RequestGuardianConsentUseCase,
    private val pendingEmail: PendingGuardianEmail,
    private val getOwnEmail: GetOwnEmailUseCase,
    private val deleteAccount: DeleteAccountUseCase,
    private val signOut: SignOutUseCase,
    private val refreshTrigger: SessionRefreshTrigger
) : MviViewModel<GuardianWaitState, GuardianWaitIntent, UiEffect>(GuardianWaitState()) {
    private var sendPending = false
    private var deletePending = false
    private var signOutPending = false

    private val busy get() = sendPending || deletePending || signOutPending

    override fun onIntent(intent: GuardianWaitIntent) {
        when (intent) {
            GuardianWaitIntent.Resend, GuardianWaitIntent.SubmitNewEmail -> {
                if (busy) return
                sendPending = true
            }
            GuardianWaitIntent.DeleteAccount -> {
                if (busy) return
                deletePending = true
            }
            GuardianWaitIntent.SignOut -> {
                if (busy) return
                signOutPending = true
            }
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: GuardianWaitIntent) = when (intent) {
        is GuardianWaitIntent.SessionUpdated -> {
            // Una sola emisión: sin un fotograma con el email enmascarado pero sin saber si se puede reenviar.
            val canResend = pendingEmail.email != null
            setState {
                val updated = GuardianWaitMutation.SessionUpdated(intent.emailMasked, intent.sends)
                GuardianWaitReducer.reduce(
                    GuardianWaitReducer.reduce(this, updated),
                    GuardianWaitMutation.CanResend(canResend)
                )
            }
        }
        GuardianWaitIntent.Resend -> onResend()
        GuardianWaitIntent.ChangeEmail -> reduce(GuardianWaitMutation.ChangeEmailStarted)
        GuardianWaitIntent.CancelChangeEmail -> reduce(GuardianWaitMutation.ChangeEmailCancelled)
        is GuardianWaitIntent.EmailChanged -> reduce(GuardianWaitMutation.EmailChanged(intent.email))
        GuardianWaitIntent.SubmitNewEmail -> onSubmitNewEmail()
        GuardianWaitIntent.DeleteAccount -> onDelete()
        GuardianWaitIntent.SignOut -> onSignOut()
    }

    // `send` levanta el bloqueo (`sendPending`) justo antes de publicar el resultado; los descartes locales lo hacen aquí.
    private suspend fun onResend() {
        reduce(GuardianWaitMutation.ResendRequested)
        val email = pendingEmail.email
        // El reducer ya descartó el reenvío sin email recordado u ocupado: sin carga no hay nada que enviar.
        if (!state.value.isLoading || email == null) {
            sendPending = false
            return
        }
        send(email, GuardianWaitNotice.RESENT)
    }

    private suspend fun onSubmitNewEmail() {
        reduce(GuardianWaitMutation.SubmitNewEmailRequested)
        val current = state.value
        // Un formato inválido no pasa de aquí: ni siquiera sale del dispositivo.
        if (!current.isLoading) {
            sendPending = false
            return
        }
        if (isOwnEmail(current.email)) {
            sendPending = false
            reduce(GuardianWaitMutation.OwnEmailRejected)
            return
        }
        send(current.email, GuardianWaitNotice.EMAIL_CHANGED)
    }

    private suspend fun send(email: String, notice: GuardianWaitNotice) {
        val result = runCatchingNonCancellation { requestConsent(email) }
        // El bloqueo se levanta antes de publicar el resultado: la interfaz nunca ofrece una acción que el VM descartaría.
        sendPending = false
        result.fold(
            onSuccess = { reduce(GuardianWaitMutation.Succeeded(notice)) },
            onFailure = {
                reduce(
                    when (val outcome = it.toGuardianOutcome()) {
                        GuardianOutcome.EmailRejected -> GuardianWaitMutation.EmailRejected
                        GuardianOutcome.AlreadyApproved -> {
                            // La sesión puede no haberlo visto aún: que se resuelva de nuevo.
                            refreshTrigger.requestRefresh()
                            GuardianWaitMutation.AlreadyApproved
                        }
                        is GuardianOutcome.Failed ->
                            GuardianWaitMutation.Failed(outcome.error, outcome.retryAfterSeconds)
                    }
                )
            }
        )
    }

    private suspend fun onDelete() {
        try {
            reduce(GuardianWaitMutation.DeleteStarted)
            // Éxito: el repositorio cierra la sesión y el host sustituye la pantalla; no se afirma nada más.
            runCatchingNonCancellation { deleteAccount() }.fold(
                onSuccess = { reduce(GuardianWaitMutation.DeleteSucceeded) },
                onFailure = { reduce(GuardianWaitMutation.DeleteFailed(it.toDeleteError())) }
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

    private fun reduce(mutation: GuardianWaitMutation) = setState { GuardianWaitReducer.reduce(this, mutation) }
}
