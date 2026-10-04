package com.violinstudio.ui.feature.auth.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.usecase.CheckEmailVerifiedUseCase
import com.violinstudio.domain.feature.auth.usecase.SendEmailVerificationUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.ui.commons.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * No navega: cuando el email queda verificado, `CheckEmailVerifiedUseCase` hace que la sesión se reemita y el host
 * de navegación sustituye esta pantalla. Ningún fallo se propaga fuera de [handleIntent] (mataría el bucle de
 * intents) ni se registra su causa: puede contener datos personales.
 */
private const val VERIFIED_FALLBACK_MILLIS = 10_000L

@HiltViewModel
class VerifyEmailViewModel @Inject constructor(
    private val checkEmailVerified: CheckEmailVerifiedUseCase,
    private val sendEmailVerification: SendEmailVerificationUseCase,
    private val signOut: SignOutUseCase
) : MviViewModel<VerifyEmailState, VerifyEmailIntent, VerifyEmailEffect>(VerifyEmailState()) {
    private var cooldown: Job? = null
    private var verifiedFallback: Job? = null

    // Los intents se procesan en serie: con dos toques seguidos el segundo se atendería cuando el primero ya terminó
    // y volvería a llamar al servidor. Se descarta al encolarlo mientras el anterior sigue pendiente.
    private var checkPending = false

    override fun onIntent(intent: VerifyEmailIntent) {
        if (intent == VerifyEmailIntent.CheckNow) {
            if (checkPending) return
            checkPending = true
        }
        super.onIntent(intent)
    }

    override suspend fun handleIntent(intent: VerifyEmailIntent) = when (intent) {
        VerifyEmailIntent.CheckNow -> onCheckNow()
        VerifyEmailIntent.Resend -> onResend()
        VerifyEmailIntent.SignOut -> onSignOut()
        is VerifyEmailIntent.DeleteActiveChanged -> reduce(VerifyEmailMutation.DeleteActiveChanged(intent.active))
    }

    private suspend fun onCheckNow() {
        try {
            if (!state.value.canCheck) return
            reduce(VerifyEmailMutation.CheckStarted)
            val result = try {
                checkEmailVerified()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Result.failure(AuthFailure.Unknown())
            }
            result.fold(
                onSuccess = { verified ->
                    if (verified) onVerified() else reduce(VerifyEmailMutation.CheckedStillUnverified)
                },
                onFailure = { reduce(VerifyEmailMutation.CheckFailed(it.toMessage())) }
            )
        } finally {
            checkPending = false
        }
    }

    // La sesión reemplaza esta pantalla al avanzar; si no lo hace a tiempo, se vuelve a permitir actuar.
    private fun onVerified() {
        reduce(VerifyEmailMutation.CheckedVerified)
        verifiedFallback?.cancel()
        verifiedFallback = viewModelScope.launch {
            delay(VERIFIED_FALLBACK_MILLIS)
            reduce(VerifyEmailMutation.VerifiedTimedOut)
        }
    }

    private suspend fun onResend() {
        if (!state.value.canResend) return
        val result = try {
            sendEmailVerification()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Result.failure(AuthFailure.Unknown())
        }
        result.fold(
            onSuccess = {
                reduce(VerifyEmailMutation.ResendSent)
                startCooldown()
            },
            onFailure = {
                val message = it.toMessage()
                reduce(VerifyEmailMutation.ResendFailed(message))
                if (message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS) startCooldown()
            }
        )
    }

    private suspend fun onSignOut() {
        try {
            signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin registrar la causa; el usuario puede reintentar y la sesión sigue su curso.
        }
    }

    private fun startCooldown() {
        cooldown?.cancel()
        cooldown = viewModelScope.launch {
            for (remaining in RESEND_COOLDOWN_SECONDS - 1 downTo 0) {
                delay(1_000)
                reduce(VerifyEmailMutation.CooldownTick(remaining))
            }
        }
    }

    private fun Throwable.toMessage() = when (this) {
        AuthFailure.TooManyRequests -> VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS
        AuthFailure.Network -> VerifyEmailMessage.NETWORK
        else -> VerifyEmailMessage.UNKNOWN
    }

    private fun reduce(mutation: VerifyEmailMutation) = setState { VerifyEmailReducer.reduce(this, mutation) }
}
