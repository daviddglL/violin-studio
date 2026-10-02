package com.violinstudio.ui.feature.auth.view

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private enum class AuthScreen { LOGIN, REGISTER, RESET }

/** Slot `auth` del host de sesión: Login, Registro y Reset reales. */
@Composable
fun AuthRoute() = AuthFlow(
    login = { onRegister, onForgot -> LoginRoute(onRegister, onForgot) },
    register = { onBack -> RegisterRoute(onBack) },
    reset = { onBack -> ResetPasswordRoute(onBack) }
)

/**
 * Navegación interna de autenticación (Login, Registro, Reset). Nunca navega a rutas de negocio: tras acceder, la
 * sesión cambia y el host de navegación sustituye todo este flujo.
 */
@Composable
fun AuthFlow(
    login: @Composable (onRegister: () -> Unit, onForgotPassword: () -> Unit) -> Unit,
    register: @Composable (onBack: () -> Unit) -> Unit,
    reset: @Composable (onBack: () -> Unit) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(AuthScreen.LOGIN) }
    BackHandler(enabled = screen != AuthScreen.LOGIN) { screen = AuthScreen.LOGIN }
    when (screen) {
        AuthScreen.LOGIN -> login({ screen = AuthScreen.REGISTER }, { screen = AuthScreen.RESET })
        AuthScreen.REGISTER -> register { screen = AuthScreen.LOGIN }
        AuthScreen.RESET -> reset { screen = AuthScreen.LOGIN }
    }
}
