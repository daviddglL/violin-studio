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
import com.violinstudio.ui.feature.auth.viewmodel.LoginError
import com.violinstudio.ui.feature.auth.viewmodel.LoginFieldError
import com.violinstudio.ui.feature.auth.viewmodel.LoginIntent
import com.violinstudio.ui.feature.auth.viewmodel.LoginState
import com.violinstudio.ui.feature.auth.viewmodel.LoginViewModel

@Composable
fun LoginRoute(onRegister: () -> Unit, onForgotPassword: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(state, viewModel::onIntent, onRegister, onForgotPassword)
}

/** No navega a rutas de negocio: registro y recuperación son navegación interna de autenticación. */
@Composable
fun LoginScreen(
    state: LoginState,
    onIntent: (LoginIntent) -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit
) {
    AuthScaffold(LOGIN_TAG, stringResource(R.string.login_title)) {
        AuthTextField(
            value = state.email,
            onValueChange = { onIntent(LoginIntent.EmailChanged(it)) },
            label = stringResource(R.string.auth_email_label),
            error = state.emailError?.let { stringResource(it.textRes()) },
            tag = AUTH_EMAIL_TAG,
            autofillTypes = listOf(AutofillType.Username, AutofillType.EmailAddress)
        )
        AuthTextField(
            value = state.password,
            onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
            label = stringResource(R.string.auth_password_label),
            error = state.passwordError?.let { stringResource(it.textRes()) },
            tag = AUTH_PASSWORD_TAG,
            autofillTypes = listOf(AutofillType.Password),
            isPassword = true,
            onDone = { onIntent(LoginIntent.Submit) }
        )
        state.error?.let { AuthMessage(stringResource(it.textRes()), isError = true) }
        AuthSubmitButton(
            label = stringResource(if (state.isLoading) R.string.login_loading else R.string.login_submit),
            enabled = state.canSubmit,
            onClick = { onIntent(LoginIntent.Submit) }
        )
        TextButton(onClick = onForgotPassword) { Text(stringResource(R.string.login_forgot)) }
        TextButton(onClick = onRegister) { Text(stringResource(R.string.login_to_register)) }
    }
}

private fun LoginFieldError.textRes() = when (this) {
    LoginFieldError.EMAIL_EMPTY -> R.string.auth_field_email_empty
    LoginFieldError.PASSWORD_EMPTY -> R.string.auth_field_password_empty
}

private fun LoginError.textRes() = when (this) {
    LoginError.INVALID_CREDENTIALS -> R.string.login_error_invalid
    LoginError.TOO_MANY_REQUESTS -> R.string.auth_error_too_many
    LoginError.NETWORK -> R.string.auth_error_network
    LoginError.GOOGLE_UNAVAILABLE -> R.string.auth_google_unavailable
    LoginError.ACCOUNT_EXISTS_OTHER_PROVIDER -> R.string.auth_google_other_provider
}
