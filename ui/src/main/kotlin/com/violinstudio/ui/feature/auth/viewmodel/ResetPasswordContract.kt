package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

enum class ResetFieldError { EMAIL_EMPTY, EMAIL_INVALID }

enum class ResetError { TOO_MANY_REQUESTS, NETWORK, UNKNOWN }

/** [sent] es la misma respuesta ("si existe, recibirás un correo") exista o no la cuenta. */
data class ResetPasswordState(
    val email: String = "",
    val emailError: ResetFieldError? = null,
    val isLoading: Boolean = false,
    val sent: Boolean = false,
    val error: ResetError? = null
) : UiState {
    override fun toString(): String =
        "ResetPasswordState(isLoading=$isLoading, sent=$sent, emailError=$emailError, error=$error)"
}

sealed interface ResetPasswordIntent : UiIntent {
    data class EmailChanged(val value: String) : ResetPasswordIntent
    data object Submit : ResetPasswordIntent
    data object ScreenLeft : ResetPasswordIntent
}

sealed interface ResetPasswordEffect : UiEffect

sealed interface ResetPasswordMutation {
    data class EmailChanged(val value: String) : ResetPasswordMutation
    data object SubmitRequested : ResetPasswordMutation
    data object Sent : ResetPasswordMutation
    data object ScreenLeft : ResetPasswordMutation
    data object EmailRejected : ResetPasswordMutation
    data class Failed(val error: ResetError) : ResetPasswordMutation
}
