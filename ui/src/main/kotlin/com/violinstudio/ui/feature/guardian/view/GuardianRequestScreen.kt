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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.view.AUTH_EMAIL_TAG
import com.violinstudio.ui.feature.auth.view.AuthMessage
import com.violinstudio.ui.feature.auth.view.AuthScaffold
import com.violinstudio.ui.feature.auth.view.AuthSubmitButton
import com.violinstudio.ui.feature.auth.view.AuthTextField
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianEmailError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestError
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestIntent
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestState
import com.violinstudio.ui.feature.guardian.viewmodel.GuardianRequestViewModel

const val GUARDIAN_REQUEST_TAG = "guardian_request"
const val GUARDIAN_REQUEST_DELETE_TAG = "guardian_request_delete"
const val GUARDIAN_REQUEST_SIGN_OUT_TAG = "guardian_request_sign_out"

/** La sesión (`ConsentPending` de un menor) entrega el motivo; la sesión también sustituye la pantalla al enviar. */
@Composable
fun GuardianRequestRoute(pending: SessionState.ConsentPending, viewModel: GuardianRequestViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onIntent by rememberUpdatedState(viewModel::onIntent)
    LaunchedEffect(pending.reason) { onIntent(GuardianRequestIntent.SessionUpdated(pending.reason)) }
    // La razón sale de la sesión desde la primera composición: sin un fotograma con el texto equivocado.
    GuardianRequestScreen(state.copy(reason = pending.reason), viewModel::onIntent)
}

/** Modo menor del consentimiento: pedir el email del tutor. No muestra nada de un tutor ya existente. */
@Composable
fun GuardianRequestScreen(state: GuardianRequestState, onIntent: (GuardianRequestIntent) -> Unit) {
    AuthScaffold(GUARDIAN_REQUEST_TAG, stringResource(state.reason.titleRes())) {
        Text(
            stringResource(state.reason.introRes()),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.succeeded) {
            // La sesión sustituirá la pantalla; mientras, se dice qué pasó en vez de dejar un formulario muerto.
            val sent = if (state.alreadyApproved) {
                R.string.guardian_request_already_approved
            } else {
                R.string.guardian_request_sent
            }
            AuthMessage(stringResource(sent), isError = false)
        } else {
            AuthTextField(
                value = state.email,
                onValueChange = { onIntent(GuardianRequestIntent.EmailChanged(it)) },
                label = stringResource(R.string.guardian_request_email_label),
                error = state.emailError?.let { stringResource(it.textRes()) },
                tag = AUTH_EMAIL_TAG,
                // El email es de otra persona: sin autorrelleno de las credenciales del propio usuario.
                autofillTypes = emptyList<AutofillType>(),
                onDone = { onIntent(GuardianRequestIntent.SubmitGuardianEmail) }
            )
            state.error?.let { AuthMessage(it.message(state.retryAfterSeconds), isError = true) }
            AuthSubmitButton(
                label = stringResource(
                    if (state.isLoading) R.string.guardian_request_sending else R.string.guardian_request_submit
                ),
                enabled = state.canSubmit,
                onClick = { onIntent(GuardianRequestIntent.SubmitGuardianEmail) }
            )
        }
        state.deleteError?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        // Sin tutor no hay otra salida de esta pantalla que borrar la cuenta o cerrar sesión.
        OutlinedButton(
            onClick = { onIntent(GuardianRequestIntent.DeleteAccount) },
            enabled = !state.isDeleting && !state.isLoading,
            modifier = Modifier.fillMaxWidth().testTag(GUARDIAN_REQUEST_DELETE_TAG)
        ) {
            Text(stringResource(if (state.isDeleting) R.string.consent_deleting else R.string.consent_delete))
        }
        TextButton(
            onClick = { onIntent(GuardianRequestIntent.SignOut) },
            modifier = Modifier.testTag(GUARDIAN_REQUEST_SIGN_OUT_TAG)
        ) { Text(stringResource(R.string.session_sign_out)) }
    }
}

private fun ConsentReason.titleRes() = when (this) {
    ConsentReason.FIRST -> R.string.guardian_request_title_first
    ConsentReason.POLICY_UPDATED -> R.string.guardian_request_title_updated
    ConsentReason.REVOKED -> R.string.guardian_request_title_revoked
}

private fun ConsentReason.introRes() = when (this) {
    ConsentReason.FIRST -> R.string.guardian_request_intro_first
    ConsentReason.POLICY_UPDATED -> R.string.guardian_request_intro_updated
    ConsentReason.REVOKED -> R.string.guardian_request_intro_revoked
}

@Composable
internal fun GuardianRequestError.message(retryAfterSeconds: Long?): String = when (this) {
    GuardianRequestError.NETWORK -> stringResource(R.string.guardian_request_error_network)
    GuardianRequestError.NOT_MINOR -> stringResource(R.string.guardian_request_error_not_minor)
    GuardianRequestError.RATE_LIMITED ->
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            // Siempre hacia arriba: nunca se promete una espera menor que la real.
            val minutes = ((retryAfterSeconds + 59) / 60).toInt()
            stringResource(R.string.guardian_request_error_rate_limited_minutes, minutes)
        } else {
            stringResource(R.string.guardian_request_error_rate_limited)
        }
    GuardianRequestError.UNAVAILABLE -> stringResource(R.string.guardian_request_error_unavailable)
    GuardianRequestError.UNKNOWN ->
        stringResource(R.string.guardian_request_error_unknown)
}

internal fun ConsentDeleteError.textRes() = when (this) {
    ConsentDeleteError.REAUTH_REQUIRED -> R.string.consent_delete_reauth
    ConsentDeleteError.FAILED -> R.string.consent_delete_failed
    ConsentDeleteError.NETWORK -> R.string.consent_delete_network
}

internal fun GuardianEmailError.textRes() = when (this) {
    GuardianEmailError.INVALID -> R.string.guardian_request_email_invalid
    GuardianEmailError.OWN_EMAIL -> R.string.guardian_request_email_own
}
