package com.violinstudio.ui.feature.auth.view

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.violinstudio.ui.feature.auth.viewmodel.LoginIntent
import com.violinstudio.ui.feature.auth.viewmodel.LoginViewModel
import com.violinstudio.ui.feature.auth.viewmodel.RegisterIntent
import com.violinstudio.ui.feature.auth.viewmodel.RegisterViewModel
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordIntent
import com.violinstudio.ui.feature.auth.viewmodel.ResetPasswordViewModel

enum class AuthScreen { LOGIN, REGISTER, RESET }

/**
 * Slot `auth` del host de sesión: Login, Registro y Reset reales. Los ViewModels viven mientras dure el flujo; al
 * salir de una pantalla se le avisa para que olvide la contraseña y los mensajes (nunca un "recibirás un correo"
 * viejo al volver).
 */
@Composable
fun AuthRoute() {
    val loginViewModel: LoginViewModel = hiltViewModel()
    val registerViewModel: RegisterViewModel = hiltViewModel()
    val resetViewModel: ResetPasswordViewModel = hiltViewModel()
    AuthFlow(
        login = { onRegister, onForgot -> LoginRoute(onRegister, onForgot, loginViewModel) },
        register = { onBack -> RegisterRoute(onBack, registerViewModel) },
        reset = { onBack -> ResetPasswordRoute(onBack, resetViewModel) },
        onLeave = {
            when (it) {
                AuthScreen.LOGIN -> loginViewModel.onIntent(LoginIntent.ScreenLeft)
                AuthScreen.REGISTER -> registerViewModel.onIntent(RegisterIntent.ScreenLeft)
                AuthScreen.RESET -> resetViewModel.onIntent(ResetPasswordIntent.ScreenLeft)
            }
        }
    )
}

/**
 * Navegación interna de autenticación (Login, Registro, Reset). Nunca navega a rutas de negocio: tras acceder, la
 * sesión cambia y el host de navegación sustituye todo este flujo. [onLeave] avisa de la pantalla que se abandona.
 */
@Composable
fun AuthFlow(
    login: @Composable (onRegister: () -> Unit, onForgotPassword: () -> Unit) -> Unit,
    register: @Composable (onBack: () -> Unit) -> Unit,
    reset: @Composable (onBack: () -> Unit) -> Unit,
    onLeave: (AuthScreen) -> Unit = {}
) {
    var screen by rememberSaveable { mutableStateOf(AuthScreen.LOGIN) }
    fun goTo(next: AuthScreen) {
        onLeave(screen)
        screen = next
    }
    BackHandler(enabled = screen != AuthScreen.LOGIN) { goTo(AuthScreen.LOGIN) }
    when (screen) {
        AuthScreen.LOGIN -> login({ goTo(AuthScreen.REGISTER) }, { goTo(AuthScreen.RESET) })
        AuthScreen.REGISTER -> register { goTo(AuthScreen.LOGIN) }
        AuthScreen.RESET -> reset { goTo(AuthScreen.LOGIN) }
    }
}
