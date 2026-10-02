package com.violinstudio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.feature.home.view.HomeRoute
import com.violinstudio.ui.feature.session.view.OfflineScreen
import com.violinstudio.ui.feature.session.view.PlaceholderScreen
import com.violinstudio.ui.feature.session.view.SplashScreen
import com.violinstudio.ui.feature.session.viewmodel.SessionIntent
import com.violinstudio.ui.feature.session.viewmodel.SessionViewModel

@Composable
fun AppNavHost(viewModel: SessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionNavHost(
        session = state.session,
        onSignOut = { viewModel.onIntent(SessionIntent.SignOut) }
    )
}

/**
 * Único sitio donde la sesión decide la navegación: las pantallas de negocio no la conocen. Los slots son los
 * destinos que construyen 5b/6a/6b; hasta entonces son marcadores.
 */
@Composable
fun SessionNavHost(
    session: SessionState,
    onSignOut: () -> Unit,
    navController: NavHostController = rememberNavController(),
    home: @Composable () -> Unit = { HomeRoute() },
    auth: @Composable () -> Unit = { PlaceholderScreen("auth") },
    verifyEmail: @Composable (email: String?) -> Unit = { PlaceholderScreen("verify_email") },
    onboarding: @Composable () -> Unit = { PlaceholderScreen("onboarding") },
    consent: @Composable () -> Unit = { PlaceholderScreen("consent") },
    guardianWait: @Composable () -> Unit = { PlaceholderScreen("guardian_wait") }
) {
    SessionRedirect(session, navController)
    NavHost(navController = navController, startDestination = SplashDestination) {
        composable<SplashDestination> { SplashScreen() }
        composable<OfflineDestination> { OfflineScreen(onSignOut) }
        composable<AuthDestination> { auth() }
        composable<VerifyEmailDestination> { verifyEmail((session as? SessionState.EmailUnverified)?.email) }
        composable<OnboardingDestination> { onboarding() }
        composable<ConsentDestination> { consent() }
        composable<GuardianWaitDestination> { guardianWait() }
        // Defensa en profundidad: aunque un back stack restaurado o un enlace caiga aquí sin Ready, no se compone
        // contenido de negocio mientras la redirección está en curso.
        composable<HomeDestination> { if (session is SessionState.Ready) home() else SplashScreen() }
    }
}

/**
 * Lleva la pila a la ruta raíz de [session] y la limpia entera (`popUpTo` inclusivo), de modo que atrás nunca
 * vuelve a un estado anterior (p. ej. tras cerrar sesión). Se reevalúa también cuando cambia el destino actual:
 * un back stack restaurado o un enlace a una ruta de negocio sin `Ready` se redirigen.
 */
@Composable
private fun SessionRedirect(session: SessionState, navController: NavHostController) {
    val current by navController.currentBackStackEntryAsState()
    LaunchedEffect(session, current) {
        val target = session.rootRoute()
        if (current?.destination?.hasRoute(target::class) != true) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
