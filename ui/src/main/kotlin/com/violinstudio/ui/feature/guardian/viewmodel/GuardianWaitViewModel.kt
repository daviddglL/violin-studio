package com.violinstudio.ui.feature.guardian.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnUidUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Espera mientras el tutor confirma. Solo hay reenviar (a la dirección que la app recuerda en memoria para este
 * usuario), cambiar el email, comprobar de nuevo, borrar la cuenta y cerrar sesión. Reenviar y cambiar pasan por el
 * mismo caso de uso: que el servidor invalide el enlace anterior y cuente los límites es cosa suya. Nunca se registra
 * ni se muestra el email del tutor.
 */
@HiltViewModel
class GuardianWaitViewModel @Inject constructor(
    private val requestConsent: RequestGuardianConsentUseCase,
    private val pendingEmail: PendingGuardianEmail,
    private val getOwnEmail: GetOwnEmailUseCase,
    private val getOwnUid: GetOwnUidUseCase,
    private val signOut: SignOutUseCase,
    private val refreshTrigger: SessionRefreshTrigger
) : MviViewModel<GuardianWaitState, GuardianWaitIntent, UiEffect>(GuardianWaitState()) {
    private var sendPending = false
    private var signOutPending = false

    private val busy get() = sendPending || signOutPending

    /** Ademas, el borrado compartido abierto (lo fija la pantalla) bloquea toda accion. */
    private val blocked get() = busy || state.value.deleteActive

    /** Los flags se activan al encolar (los intents se procesan de uno en uno) para descartar los que llegan después. */
    override fun onIntent(intent: GuardianWaitIntent) {
        when (intent) {
            GuardianWaitIntent.Resend, GuardianWaitIntent.SubmitNewEmail -> {
                if (blocked) return
                sendPending = true
            }
            GuardianWaitIntent.SignOut -> {
                if (blocked) return
                signOutPending = true
            }
            GuardianWaitIntent.ChangeEmail,
            GuardianWaitIntent.CancelChangeEmail,
            is GuardianWaitIntent.EmailChanged -> if (blocked) return
            else -> Unit
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: GuardianWaitIntent) = when (intent) {
        is GuardianWaitIntent.SessionUpdated -> {
            // Una sola emisión: sin un fotograma con el email enmascarado pero sin saber si se puede reenviar.
            val canResend = rememberedEmail() != null
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
        is GuardianWaitIntent.DeleteActiveChanged -> reduce(GuardianWaitMutation.DeleteActiveChanged(intent.active))
        GuardianWaitIntent.CheckAgain -> onCheckAgain()
        GuardianWaitIntent.RetryWaitElapsed -> reduce(GuardianWaitMutation.RetryWaitElapsed)
        GuardianWaitIntent.SignOut -> onSignOut()
    }

    private fun onCheckAgain() {
        // Con el borrado compartido abierto no se pide ningun refresco.
        if (state.value.deleteActive) return
        refreshTrigger.requestRefresh()
        reduce(GuardianWaitMutation.NoticeCleared)
    }

    private suspend fun onResend() {
        try {
            reduce(GuardianWaitMutation.ResendRequested)
            val email = rememberedEmail()
            // El reducer ya descartó el reenvío sin email recordado u ocupado: sin carga no hay nada que enviar.
            if (!state.value.isLoading || email == null) return
            if (isOwnEmail(email)) {
                reduce(GuardianWaitMutation.OwnEmailRejected)
                return
            }
            send(email, GuardianWaitNotice.RESENT)
        } finally {
            sendPending = false
        }
    }

    private suspend fun onSubmitNewEmail() {
        try {
            reduce(GuardianWaitMutation.SubmitNewEmailRequested)
            val current = state.value
            // Un formato inválido no pasa de aquí: ni siquiera sale del dispositivo.
            if (!current.isLoading) return
            if (isOwnEmail(current.email)) {
                reduce(GuardianWaitMutation.OwnEmailRejected)
                return
            }
            send(current.email, GuardianWaitNotice.EMAIL_CHANGED)
        } finally {
            sendPending = false
        }
    }

    private suspend fun send(email: String, notice: GuardianWaitNotice) {
        runCatchingNonCancellation { requestConsent(email) }.fold(
            onSuccess = { reduce(GuardianWaitMutation.Succeeded(notice, it.emailMasked)) },
            onFailure = {
                reduce(
                    when (val outcome = it.toGuardianOutcome()) {
                        GuardianOutcome.EmailRejected -> GuardianWaitMutation.EmailRejected
                        GuardianOutcome.AlreadyApproved -> {
                            // La sesión puede no haberlo visto aún: que se resuelva de nuevo.
                            refreshTrigger.requestRefresh()
                            GuardianWaitMutation.AlreadyApproved
                        }
                        is GuardianOutcome.Failed -> {
                            outcome.retryAfterSeconds?.takeIf { s -> s > 0 }?.let(::liftResendBlockAfter)
                            GuardianWaitMutation.Failed(outcome.error, outcome.retryAfterSeconds)
                        }
                    }
                )
            }
        )
    }

    /** Pasada la espera que pidió el servidor, reenviar vuelve a ofrecerse. */
    private fun liftResendBlockAfter(seconds: Long) {
        viewModelScope.launch {
            delay(seconds * MILLIS_PER_SECOND)
            onIntent(GuardianWaitIntent.RetryWaitElapsed)
        }
    }

    private suspend fun onSignOut() {
        try {
            reduce(GuardianWaitMutation.SignOutStarted)
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa; la sesión sigue siendo la fuente de verdad.
        } finally {
            reduce(GuardianWaitMutation.SignOutFinished)
            signOutPending = false
        }
    }

    /** El email recordado es del usuario actual o no es nada: sin uid conocido no hay reenvío. */
    private suspend fun rememberedEmail(): String? {
        val uid = runCatchingNonCancellation { Result.success(getOwnUid()) }.getOrNull() ?: return null
        return pendingEmail.emailFor(uid)
    }

    /** Comparación normalizada: sin mayúsculas ni espacios. Sin email propio conocido no se bloquea nada. */
    private suspend fun isOwnEmail(typed: String): Boolean {
        val own = runCatchingNonCancellation { Result.success(getOwnEmail()) }.getOrNull() ?: return false
        return own.trim().equals(typed.trim(), ignoreCase = true)
    }

    private fun reduce(mutation: GuardianWaitMutation) = setState { GuardianWaitReducer.reduce(this, mutation) }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
