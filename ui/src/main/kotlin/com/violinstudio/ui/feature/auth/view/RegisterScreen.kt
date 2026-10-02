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
import com.violinstudio.ui.feature.auth.viewmodel.RegisterError
import com.violinstudio.ui.feature.auth.viewmodel.RegisterFieldError
import com.violinstudio.ui.feature.auth.viewmodel.RegisterIntent
import com.violinstudio.ui.feature.auth.viewmodel.RegisterState
import com.violinstudio.ui.feature.auth.viewmodel.RegisterViewModel

@Composable
fun RegisterRoute(onBack: () -> Unit, viewModel: RegisterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RegisterScreen(state, viewModel::onIntent, onBack)
}

@Composable
fun RegisterScreen(state: RegisterState, onIntent: (RegisterIntent) -> Unit, onBack: () -> Unit) {
    AuthScaffold(REGISTER_TAG, stringResource(R.string.register_title)) {
        AuthTextField(
            value = state.email,
            onValueChange = { onIntent(RegisterIntent.EmailChanged(it)) },
            label = stringResource(R.string.auth_email_label),
            error = state.emailError?.let { stringResource(it.textRes()) },
            tag = AUTH_EMAIL_TAG,
            autofillTypes = listOf(AutofillType.Username, AutofillType.EmailAddress)
        )
        AuthTextField(
            value = state.password,
            onValueChange = { onIntent(RegisterIntent.PasswordChanged(it)) },
            label = stringResource(R.string.auth_password_label),
            error = state.passwordError?.let { stringResource(it.textRes()) },
            tag = AUTH_PASSWORD_TAG,
            autofillTypes = listOf(AutofillType.NewPassword),
            isPassword = true,
            onDone = { onIntent(RegisterIntent.Submit) }
        )
        state.error?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        AuthSubmitButton(
            label = stringResource(if (state.isLoading) R.string.register_loading else R.string.register_submit),
            enabled = state.canSubmit,
            onClick = { onIntent(RegisterIntent.Submit) }
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.auth_back_to_login)) }
    }
}

private fun RegisterFieldError.textRes() = when (this) {
    RegisterFieldError.EMAIL_EMPTY -> R.string.auth_field_email_empty
    RegisterFieldError.EMAIL_INVALID -> R.string.auth_field_email_invalid
    RegisterFieldError.PASSWORD_EMPTY -> R.string.auth_field_password_empty
    RegisterFieldError.PASSWORD_TOO_SHORT -> R.string.register_password_too_short
    RegisterFieldError.PASSWORD_WEAK -> R.string.register_password_weak
}

private fun RegisterError.textRes() = when (this) {
    RegisterError.ACCOUNT_UNAVAILABLE -> R.string.register_error_unavailable
    RegisterError.TOO_MANY_REQUESTS -> R.string.auth_error_too_many
    RegisterError.NETWORK -> R.string.auth_error_network
    RegisterError.UNKNOWN -> R.string.auth_error_unknown
    RegisterError.GOOGLE_UNAVAILABLE -> R.string.auth_google_unavailable
    RegisterError.ACCOUNT_EXISTS_OTHER_PROVIDER -> R.string.auth_google_other_provider
}
