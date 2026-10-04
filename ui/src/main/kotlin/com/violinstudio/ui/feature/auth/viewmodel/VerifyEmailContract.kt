package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

const val RESEND_COOLDOWN_SECONDS = 60

/** Mensaje informativo o de error que la pantalla traduce a texto. No lleva datos personales. */
enum class VerifyEmailMessage {
    NOT_VERIFIED_YET,
    RESEND_SENT,
    WAIT_TOO_MANY_REQUESTS,
    NETWORK,
    UNKNOWN,
    VERIFIED_CONTINUING
}

/**
 * El email que se muestra lo aporta la sesión (no vive aquí). Que el email quede verificado no se refleja en esta
 * pantalla: la sesión avanza sola y el host de navegación la sustituye.
 */
data class VerifyEmailState(
    val checking: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val verified: Boolean = false,
    val message: VerifyEmailMessage? = null,

    /** Solo la pantalla lo fija: el flujo compartido de borrar la cuenta esta abierto o terminado (D1). */
    val deleteActive: Boolean = false
) : UiState {
    val canResend: Boolean get() = resendCooldownSeconds == 0 && !verified && !deleteActive
    val canCheck: Boolean get() = !checking && !verified && !deleteActive
}

sealed interface VerifyEmailIntent : UiIntent {
    data object CheckNow : VerifyEmailIntent
    data object Resend : VerifyEmailIntent
    data object SignOut : VerifyEmailIntent
    data class DeleteActiveChanged(val active: Boolean) : VerifyEmailIntent
}

/** Sin efectos: no navega, la sesión decide. */
sealed interface VerifyEmailEffect : UiEffect

sealed interface VerifyEmailMutation {
    data object CheckStarted : VerifyEmailMutation
    data object CheckedStillUnverified : VerifyEmailMutation
    data object CheckedVerified : VerifyEmailMutation
    data object VerifiedTimedOut : VerifyEmailMutation
    data class CheckFailed(val message: VerifyEmailMessage) : VerifyEmailMutation
    data object ResendSent : VerifyEmailMutation
    data class ResendFailed(val message: VerifyEmailMessage) : VerifyEmailMutation
    data class CooldownTick(val remainingSeconds: Int) : VerifyEmailMutation
    data class DeleteActiveChanged(val active: Boolean) : VerifyEmailMutation
}
