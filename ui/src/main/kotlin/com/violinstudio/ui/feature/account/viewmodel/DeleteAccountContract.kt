package com.violinstudio.ui.feature.account.viewmodel

import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

/** IDLE: solo el boton. CONFIRMING: pide confirmacion explicita. REAUTH: pide la reautenticacion reciente. */
enum class DeleteStep { IDLE, CONFIRMING, REAUTH }

/**
 * Todos los fallos son reintentables salvo [REAUTH_UNAVAILABLE] (la cuenta no tiene un proveedor con el que
 * reautenticar: la salida es cerrar sesion y volver a entrar). Ninguno afirma que la cuenta se haya borrado.
 */
enum class DeleteAccountError {
    WRONG_PASSWORD,
    TOO_MANY_ATTEMPTS,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    REAUTH_UNAVAILABLE,
    FAILED
}

data class DeleteAccountState(
    val step: DeleteStep = DeleteStep.IDLE,
    val method: ReauthMethod? = null,
    val password: String = "",
    val isWorking: Boolean = false,
    /** Borrado hecho: la sesion pasa sola a `LoggedOut`; hasta entonces todo queda bloqueado. */
    val deleted: Boolean = false,
    val error: DeleteAccountError? = null
) : UiState {
    val canOpen: Boolean get() = step == DeleteStep.IDLE && !deleted
    val canConfirm: Boolean
        get() = step == DeleteStep.CONFIRMING && !isWorking && !deleted &&
            error != DeleteAccountError.REAUTH_UNAVAILABLE
    val canCancel: Boolean get() = step != DeleteStep.IDLE && !isWorking && !deleted
    val canSubmitPassword: Boolean
        get() = step == DeleteStep.REAUTH && method == ReauthMethod.PASSWORD && password.isNotBlank() &&
            !isWorking && !deleted
    val canReauthWithGoogle: Boolean
        get() = step == DeleteStep.REAUTH && method == ReauthMethod.GOOGLE && !isWorking && !deleted

    // Sin la contrasena.
    override fun toString() =
        "DeleteAccountState(step=$step, method=$method, isWorking=$isWorking, deleted=$deleted, error=$error)"
}

sealed interface DeleteAccountIntent : UiIntent {
    data object Open : DeleteAccountIntent
    data object Cancel : DeleteAccountIntent
    data object Confirm : DeleteAccountIntent
    data class PasswordChanged(val value: String) : DeleteAccountIntent {
        override fun toString() = "PasswordChanged(***)"
    }

    data object SubmitPassword : DeleteAccountIntent
    data class GoogleToken(val token: GoogleIdToken) : DeleteAccountIntent
    data object GoogleFailed : DeleteAccountIntent
}

sealed interface DeleteAccountMutation {
    data object Opened : DeleteAccountMutation
    data object Cancelled : DeleteAccountMutation
    data object DeleteStarted : DeleteAccountMutation
    data class ReauthRequired(val method: ReauthMethod) : DeleteAccountMutation
    data class PasswordChanged(val value: String) : DeleteAccountMutation {
        override fun toString() = "PasswordChanged(***)"
    }

    data object ReauthStarted : DeleteAccountMutation
    data class ReauthFailed(val error: DeleteAccountError) : DeleteAccountMutation
    data class DeleteFailed(val error: DeleteAccountError) : DeleteAccountMutation
    data object Deleted : DeleteAccountMutation
}

object DeleteAccountReducer {
    /** Cada mutacion solo se aplica desde el paso que la produce: una carrera no deja el formulario a medias. */
    fun reduce(state: DeleteAccountState, mutation: DeleteAccountMutation): DeleteAccountState = when (mutation) {
        DeleteAccountMutation.Opened ->
            if (state.canOpen) state.copy(step = DeleteStep.CONFIRMING, error = null) else state
        DeleteAccountMutation.Cancelled ->
            if (state.canCancel) DeleteAccountState() else state
        DeleteAccountMutation.DeleteStarted ->
            if (state.canConfirm) state.copy(isWorking = true, error = null) else state
        is DeleteAccountMutation.ReauthRequired ->
            if (state.isWorking && !state.deleted) {
                state.copy(step = DeleteStep.REAUTH, method = mutation.method, isWorking = false, error = null)
            } else {
                state
            }
        is DeleteAccountMutation.PasswordChanged ->
            if (state.step == DeleteStep.REAUTH && state.method == ReauthMethod.PASSWORD && !state.isWorking) {
                state.copy(password = mutation.value, error = null)
            } else {
                state
            }
        DeleteAccountMutation.ReauthStarted ->
            if (state.step == DeleteStep.REAUTH && !state.isWorking && !state.deleted) {
                state.copy(isWorking = true, error = null)
            } else {
                state
            }
        is DeleteAccountMutation.ReauthFailed ->
            if (state.step == DeleteStep.REAUTH && !state.deleted) {
                state.copy(
                    isWorking = false,
                    error = mutation.error,
                    password = if (mutation.error == DeleteAccountError.WRONG_PASSWORD) "" else state.password
                )
            } else {
                state
            }
        is DeleteAccountMutation.DeleteFailed ->
            if (state.isWorking) {
                state.copy(step = DeleteStep.CONFIRMING, isWorking = false, password = "", error = mutation.error)
            } else {
                state
            }
        DeleteAccountMutation.Deleted ->
            if (state.isWorking) state.copy(isWorking = false, deleted = true, password = "", error = null) else state
    }
}
