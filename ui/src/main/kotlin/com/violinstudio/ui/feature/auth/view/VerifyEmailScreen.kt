package com.violinstudio.ui.feature.auth.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailIntent
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailMessage
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailState
import com.violinstudio.ui.feature.auth.viewmodel.VerifyEmailViewModel

const val VERIFY_EMAIL_TAG = "verify_email"

/** [email] es el del propio usuario, lo aporta la sesión y nunca se registra en logs. */
@Composable
fun VerifyEmailRoute(email: String?, viewModel: VerifyEmailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VerifyEmailScreen(email = email, state = state, onIntent = viewModel::onIntent)
}

/** Solo reenviar, comprobar y cerrar sesión: no navega a nada de negocio (la sesión decide). */
@Composable
fun VerifyEmailScreen(email: String?, state: VerifyEmailState, onIntent: (VerifyEmailIntent) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).testTag(VERIFY_EMAIL_TAG),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.verify_email_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (email != null) {
                    stringResource(R.string.verify_email_message, email)
                } else {
                    stringResource(R.string.verify_email_message_no_email)
                },
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            state.message?.let {
                Text(
                    text = stringResource(it.textRes()),
                    color = if (it.isError()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("verify_email_message")
                )
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = { onIntent(VerifyEmailIntent.CheckNow) },
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Sin indicador infinito: bloquearía la sincronización de los tests de UI.
                val label = if (state.checking) R.string.verify_email_checking else R.string.verify_email_check
                Text(stringResource(label))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onIntent(VerifyEmailIntent.Resend) },
                enabled = state.canResend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.canResend) {
                        stringResource(R.string.verify_email_resend)
                    } else {
                        stringResource(R.string.verify_email_resend_wait, state.resendCooldownSeconds)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onIntent(VerifyEmailIntent.SignOut) }) {
                Text(stringResource(R.string.session_sign_out))
            }
        }
    }
}

private fun VerifyEmailMessage.textRes() = when (this) {
    VerifyEmailMessage.NOT_VERIFIED_YET -> R.string.verify_email_not_verified_yet
    VerifyEmailMessage.RESEND_SENT -> R.string.verify_email_resend_sent
    VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS -> R.string.verify_email_wait_too_many
    VerifyEmailMessage.NETWORK -> R.string.verify_email_network
    VerifyEmailMessage.UNKNOWN -> R.string.verify_email_unknown
    VerifyEmailMessage.VERIFIED_CONTINUING -> R.string.verify_email_verified_continuing
}

private fun VerifyEmailMessage.isError() = this != VerifyEmailMessage.RESEND_SENT &&
    this != VerifyEmailMessage.NOT_VERIFIED_YET
