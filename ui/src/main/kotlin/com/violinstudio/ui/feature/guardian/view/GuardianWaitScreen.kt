@file:OptIn(ExperimentalComposeUiApi::class)

package com.violinstudio.ui.feature.guardian.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.account.view.LocalDeleteAccount
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.auth.view.AuthSubmitButton
import com.violinstudio.ui.feature.auth.view.AuthTextField
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitIntent
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitNotice
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitState
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianWaitViewModel

const val GUARDIAN_WAIT_TAG = "guardian_wait"
const val GUARDIAN_WAIT_RESEND_TAG = "guardian_wait_resend"
const val GUARDIAN_WAIT_CHANGE_TAG = "guardian_wait_change"
const val GUARDIAN_WAIT_CANCEL_TAG = "guardian_wait_cancel"
const val GUARDIAN_WAIT_CHECK_TAG = "guardian_wait_check"
const val GUARDIAN_WAIT_SIGN_OUT_TAG = "guardian_wait_sign_out"

/** Destino `guardian_wait` del host de sesión. [viewModel] se inyecta para poder probarlo sin Hilt. */
@Composable
fun GuardianWaitSlot(
    pending: SessionState.ParentalPending,
    viewModel: @Composable () -> GuardianWaitViewModel = { hiltViewModel() }
) = GuardianWaitRoute(pending, viewModel())

/**
 * La sesión (`ParentalPending`) entrega el email enmascarado y los envíos; la pantalla los usa desde la primera
 * composición, sin un fotograma con el texto equivocado. Del tutor solo llega `emailMasked`.
 */
@Composable
fun GuardianWaitRoute(pending: SessionState.ParentalPending, viewModel: GuardianWaitViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onIntent by rememberUpdatedState(viewModel::onIntent)
    val deleteActive = LocalDeleteAccount.current.active
    LaunchedEffect(deleteActive) { onIntent(GuardianWaitIntent.DeleteActiveChanged(deleteActive)) }
    LaunchedEffect(pending.emailMasked, pending.sends) {
        onIntent(GuardianWaitIntent.SessionUpdated(pending.emailMasked, pending.sends))
    }
    // El enmascarado que acaba de contestar el servidor gana al de la sesión hasta que esta traiga uno más nuevo.
    GuardianWaitScreen(
        state.copy(emailMasked = state.emailMasked ?: pending.emailMasked, sends = pending.sends),
        viewModel::onIntent
    )
}

/**
 * Espera del menor. Solo ofrece reenviar (si la app recuerda la dirección), cambiar el email, comprobar de nuevo
 * (tras "ya aprobado"), borrar la cuenta y cerrar sesión; todo se bloquea mientras algo está en curso.
 */
@Composable
fun GuardianWaitScreen(state: GuardianWaitState, onIntent: (GuardianWaitIntent) -> Unit) {
    val delete = LocalDeleteAccount.current
    // Con el borrado compartido abierto (o terminado) nada mas puede empezar.
    val busy = state.busy || delete.active
    AuthScaffold(GUARDIAN_WAIT_TAG, stringResource(R.string.guardian_wait_title)) {
        Text(
            state.emailMasked?.let { stringResource(R.string.guardian_wait_intro_masked, it) }
                ?: stringResource(R.string.guardian_wait_intro),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        state.notice?.let { AuthMessage(stringResource(it.textRes()), isError = false) }
        state.error?.let { AuthMessage(it.message(state.retryAfterSeconds), isError = true) }
        if (state.notice == GuardianWaitNotice.ALREADY_APPROVED) {
            OutlinedButton(
                onClick = { onIntent(GuardianWaitIntent.CheckAgain) },
                enabled = !delete.active,
                modifier = Modifier.fillMaxWidth().testTag(GUARDIAN_WAIT_CHECK_TAG)
            ) { Text(stringResource(R.string.guardian_wait_check_again)) }
        }
        if (state.changingEmail) {
            ChangeEmailForm(state, busy, onIntent)
        } else {
            if ((state.canResend || state.resendBlocked) && !state.terminal) {
                OutlinedButton(
                    onClick = { onIntent(GuardianWaitIntent.Resend) },
                    enabled = state.canResendNow && !delete.active,
                    modifier = Modifier.fillMaxWidth().testTag(GUARDIAN_WAIT_RESEND_TAG)
                ) {
                    Text(
                        stringResource(
                            if (state.isLoading) R.string.guardian_request_sending else R.string.guardian_wait_resend
                        )
                    )
                }
            }
            if (!state.terminal) {
                OutlinedButton(
                    onClick = { onIntent(GuardianWaitIntent.ChangeEmail) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(GUARDIAN_WAIT_CHANGE_TAG)
                ) { Text(stringResource(R.string.guardian_wait_change_email)) }
            }
        }
        delete.entry(!state.busy)
        TextButton(
            onClick = { onIntent(GuardianWaitIntent.SignOut) },
            enabled = !busy,
            modifier = Modifier.testTag(GUARDIAN_WAIT_SIGN_OUT_TAG)
        ) { Text(stringResource(R.string.session_sign_out)) }
    }
}

@Composable
private fun ChangeEmailForm(state: GuardianWaitState, busy: Boolean, onIntent: (GuardianWaitIntent) -> Unit) {
    AuthTextField(
        value = state.email,
        onValueChange = { onIntent(GuardianWaitIntent.EmailChanged(it)) },
        label = stringResource(R.string.guardian_request_email_label),
        error = state.emailError?.let { stringResource(it.textRes()) },
        tag = AUTH_EMAIL_TAG,
        // El email es de otra persona: sin autorrelleno de las credenciales del propio usuario.
        autofillTypes = emptyList<AutofillType>(),
        enabled = !busy,
        onDone = { if (!busy) onIntent(GuardianWaitIntent.SubmitNewEmail) }
    )
    AuthSubmitButton(
        label = stringResource(
            if (state.isLoading) R.string.guardian_request_sending else R.string.guardian_wait_send_new
        ),
        enabled = state.canSubmitNewEmail && !busy,
        onClick = { onIntent(GuardianWaitIntent.SubmitNewEmail) }
    )
    TextButton(
        onClick = { onIntent(GuardianWaitIntent.CancelChangeEmail) },
        enabled = !busy,
        modifier = Modifier.testTag(GUARDIAN_WAIT_CANCEL_TAG)
    ) { Text(stringResource(R.string.guardian_wait_cancel)) }
}

private fun GuardianWaitNotice.textRes() = when (this) {
    GuardianWaitNotice.RESENT -> R.string.guardian_wait_resent
    GuardianWaitNotice.EMAIL_CHANGED -> R.string.guardian_wait_email_changed
    GuardianWaitNotice.ALREADY_APPROVED -> R.string.guardian_request_already_approved
}
