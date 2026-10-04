@file:OptIn(ExperimentalComposeUiApi::class)

package com.violinstudio.ui.feature.account.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountError
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountIntent
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountState
import com.violinstudio.ui.feature.account.viewmodel.DeleteStep
import com.violinstudio.ui.feature.auth.view.AuthTextField
import com.violinstudio.ui.feature.auth.view.GoogleSignInButton

const val DELETE_ACCOUNT_BUTTON_TAG = "delete_account_button"
const val DELETE_ACCOUNT_PANEL_TAG = "delete_account_panel"
const val DELETE_ACCOUNT_CONFIRM_TAG = "delete_account_confirm"
const val DELETE_ACCOUNT_CANCEL_TAG = "delete_account_cancel"
const val DELETE_ACCOUNT_PASSWORD_TAG = "delete_account_password"
const val DELETE_ACCOUNT_SUBMIT_TAG = "delete_account_submit"
const val DELETE_ACCOUNT_GOOGLE_TAG = "delete_account_google"
const val DELETE_ACCOUNT_MESSAGE_TAG = "delete_account_message"
const val DELETE_ACCOUNT_SIGN_OUT_TAG = "delete_account_sign_out"

/**
 * Punto de entrada unico al borrado de cuenta (D1): lo usan las pantallas de verificacion, onboarding, consentimiento,
 * espera del tutor y ajustes con el mismo flujo (ver [WithDeleteAccount] y [LocalDeleteAccount]). [enabled] solo
 * gobierna el boton inicial; con el flujo abierto mandan los flags del propio estado. Tras borrar, [onSignOut] (si el
 * anfitrion da uno) es la salida de emergencia por si la sesion no cambia sola.
 */
@Composable
fun DeleteAccountEntry(
    state: DeleteAccountState,
    onIntent: (DeleteAccountIntent) -> Unit,
    enabled: Boolean,
    onSignOut: (() -> Unit)? = null
) {
    when {
        state.deleted -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Message(stringResource(R.string.delete_account_done), isError = false, announced = true)
            if (onSignOut != null) {
                TextButton(onClick = onSignOut, modifier = Modifier.testTag(DELETE_ACCOUNT_SIGN_OUT_TAG)) {
                    Text(stringResource(R.string.session_sign_out))
                }
            }
        }
        state.step == DeleteStep.IDLE -> OutlinedButton(
            onClick = { onIntent(DeleteAccountIntent.Open) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_BUTTON_TAG)
        ) { Text(stringResource(R.string.delete_account_button)) }
        else -> Column(
            modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_PANEL_TAG),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            state.error?.let { Message(stringResource(it.textRes()), isError = true, announced = true) }
            if (state.step == DeleteStep.CONFIRMING) {
                Confirmation(state, onIntent)
            } else {
                Reauthentication(state, onIntent)
            }
            OutlinedButton(
                onClick = { onIntent(DeleteAccountIntent.Cancel) },
                enabled = state.canCancel,
                modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_CANCEL_TAG)
            ) { Text(stringResource(R.string.delete_account_cancel)) }
        }
    }
}

@Composable
private fun Confirmation(state: DeleteAccountState, onIntent: (DeleteAccountIntent) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Message(stringResource(R.string.delete_account_confirm_message), isError = false)
    Button(
        onClick = { onIntent(DeleteAccountIntent.Confirm) },
        enabled = state.canConfirm,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(DELETE_ACCOUNT_CONFIRM_TAG)
    ) {
        Text(
            stringResource(
                if (state.isWorking) R.string.delete_account_deleting else R.string.delete_account_confirm_yes
            )
        )
    }
}

@Composable
private fun Reauthentication(state: DeleteAccountState, onIntent: (DeleteAccountIntent) -> Unit) {
    Message(stringResource(R.string.delete_account_reauth_message), isError = false)
    if (state.method == ReauthMethod.GOOGLE) {
        GoogleSignInButton(
            enabled = state.canReauthWithGoogle,
            onToken = { onIntent(DeleteAccountIntent.GoogleToken(it)) },
            onFailed = { onIntent(DeleteAccountIntent.GoogleFailed) },
            tag = DELETE_ACCOUNT_GOOGLE_TAG,
            label = stringResource(R.string.delete_account_google)
        )
    } else {
        AuthTextField(
            value = state.password,
            onValueChange = { onIntent(DeleteAccountIntent.PasswordChanged(it)) },
            label = stringResource(R.string.delete_account_password_label),
            error = null,
            tag = DELETE_ACCOUNT_PASSWORD_TAG,
            autofillTypes = listOf(AutofillType.Password),
            isPassword = true,
            enabled = !state.isWorking,
            onDone = { onIntent(DeleteAccountIntent.SubmitPassword) }
        )
        Button(
            onClick = { onIntent(DeleteAccountIntent.SubmitPassword) },
            enabled = state.canSubmitPassword,
            modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_SUBMIT_TAG)
        ) {
            Text(
                stringResource(
                    if (state.isWorking) R.string.delete_account_deleting else R.string.delete_account_reauth_submit
                )
            )
        }
    }
}

/** Solo lo que cambia tras una accion (errores asertivos, aviso final cortes) es region viva y lleva etiqueta. */
@Composable
private fun Message(text: String, isError: Boolean, announced: Boolean = false) {
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 8.dp).then(
            if (announced) {
                Modifier.testTag(DELETE_ACCOUNT_MESSAGE_TAG).semantics {
                    liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                }
            } else {
                Modifier
            }
        )
    )
}

private fun DeleteAccountError.textRes() = when (this) {
    DeleteAccountError.WRONG_PASSWORD -> R.string.delete_account_error_wrong_password
    DeleteAccountError.TOO_MANY_ATTEMPTS -> R.string.delete_account_error_too_many
    DeleteAccountError.NETWORK -> R.string.delete_account_error_network
    DeleteAccountError.PROVIDER_UNAVAILABLE -> R.string.delete_account_error_provider
    DeleteAccountError.REAUTH_UNAVAILABLE -> R.string.delete_account_error_reauth_unavailable
    DeleteAccountError.FAILED -> R.string.delete_account_error_failed
}
