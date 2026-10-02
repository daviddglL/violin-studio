package com.violinstudio.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.ui.feature.auth.view.VerifyEmailRoute
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
    verifyEmail: @Composable (email: String?) -> Unit = { VerifyEmailRoute(it) },
    onboarding: @Composable () -> Unit = { PlaceholderScreen("onboarding") },
    consent: @Composable () -> Unit = { PlaceholderScreen("consent") },
    guardianWait: @Composable () -> Unit = { PlaceholderScreen("guardian_wait") }
) {
    // Un fallo transitorio (Ready -> Unavailable) no destruye Home ni su ViewModel: se mantiene Ready para el
    // enrutado y se superpone la pantalla sin conexión, que bloquea la interacción y solo ofrece cerrar sesión.
    // Acceso sin sesión Ready no se concede: la sesión real sigue siendo Unavailable.
    val lastRouted = remember { arrayOfNulls<SessionState>(1) }
    val routed = if (session is SessionState.Unavailable && lastRouted[0] is SessionState.Ready) {
        lastRouted[0]!!
    } else {
        session
    }
    SideEffect { lastRouted[0] = routed }
    SessionRedirect(routed, navController)
    Box {
        SessionGraph(routed, navController, onSignOut, home, auth, verifyEmail, onboarding, consent, guardianWait)
        if (session is SessionState.Unavailable && routed is SessionState.Ready) {
            Surface(Modifier.fillMaxSize()) { OfflineScreen(onSignOut) }
        }
    }
}

@Composable
private fun SessionGraph(
    session: SessionState,
    navController: NavHostController,
    onSignOut: () -> Unit,
    home: @Composable () -> Unit,
    auth: @Composable () -> Unit,
    verifyEmail: @Composable (email: String?) -> Unit,
    onboarding: @Composable () -> Unit,
    consent: @Composable () -> Unit,
    guardianWait: @Composable () -> Unit
) {
    // El email es el del último EmailUnverified: durante la transición de salida la sesión ya es otra y el slot no
    // debe quedarse sin él.
    val lastEmail = rememberLastEmail(session)
    NavHost(navController = navController, startDestination = SplashDestination) {
        composable<SplashDestination> { SplashScreen() }
        composable<OfflineDestination> { OfflineScreen(onSignOut) }
        composable<AuthDestination> { auth() }
        composable<VerifyEmailDestination> { verifyEmail(lastEmail) }
        composable<OnboardingDestination> { onboarding() }
        composable<ConsentDestination> { consent() }
        composable<GuardianWaitDestination> { guardianWait() }
        // Defensa en profundidad: aunque un back stack restaurado o un enlace caiga aquí sin Ready, no se compone
        // contenido de negocio mientras la redirección está en curso.
        composable<HomeDestination> { if (session is SessionState.Ready) home() else SplashScreen() }
    }
}

@Composable
private fun rememberLastEmail(session: SessionState): String? {
    val holder = remember { arrayOfNulls<String>(1) }
    (session as? SessionState.EmailUnverified)?.let { holder[0] = it.email }
    return holder[0]
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
