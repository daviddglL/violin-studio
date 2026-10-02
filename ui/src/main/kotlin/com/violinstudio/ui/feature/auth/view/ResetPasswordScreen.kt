@file:OptIn(ExperimentalComposeUiApi::class)

package com.violinstudio.ui.feature.auth.view

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.ui.R
import com.violinstudio.ui.feature.auth.viewmodel.ResetError
import com.violinstudio.ui.feature.auth.viewmodel.ResetFieldError
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordIntent
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordState
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordViewModel

@Composable
fun ResetPasswordRoute(onBack: () -> Unit, viewModel: ResetPasswordViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ResetPasswordScreen(state, viewModel::onIntent, onBack)
}

/** La confirmación es la misma exista o no la cuenta. */
@Composable
fun ResetPasswordScreen(state: ResetPasswordState, onIntent: (ResetPasswordIntent) -> Unit, onBack: () -> Unit) {
    AuthScaffold(RESET_TAG, stringResource(R.string.reset_title)) {
        Text(stringResource(R.string.reset_message))
        AuthTextField(
            value = state.email,
            onValueChange = { onIntent(ResetPasswordIntent.EmailChanged(it)) },
            label = stringResource(R.string.auth_email_label),
            error = state.emailError?.let { stringResource(it.textRes()) },
            tag = AUTH_EMAIL_TAG,
            autofillTypes = listOf(AutofillType.EmailAddress, AutofillType.Username),
            onDone = { onIntent(ResetPasswordIntent.Submit) }
        )
        if (state.sent) AuthMessage(stringResource(R.string.reset_sent), isError = false)
        state.error?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        AuthSubmitButton(
            label = stringResource(if (state.isLoading) R.string.reset_loading else R.string.reset_submit),
            enabled = !state.isLoading,
            onClick = { onIntent(ResetPasswordIntent.Submit) }
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.auth_back_to_login)) }
    }
}

private fun ResetFieldError.textRes() = when (this) {
    ResetFieldError.EMAIL_EMPTY -> R.string.auth_field_email_empty
    ResetFieldError.EMAIL_INVALID -> R.string.auth_field_email_invalid
}

private fun ResetError.textRes() = when (this) {
    ResetError.TOO_MANY_REQUESTS -> R.string.auth_error_too_many
    ResetError.NETWORK -> R.string.auth_error_network
    ResetError.UNKNOWN -> R.string.auth_error_unknown
}
