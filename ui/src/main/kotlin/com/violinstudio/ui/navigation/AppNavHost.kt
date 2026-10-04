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
import com.violinstudio.ui.feature.account.view.WithDeleteAccount
import com.violinstudio.ui.feature.account.viewmodel.DeleteAccountViewModel
import com.violinstudio.ui.feature.auth.view.AuthRoute
import com.violinstudio.ui.feature.auth.view.VerifyEmailRoute
import com.violinstudio.ui.feature.consent.view.ConsentSlot
import com.violinstudio.ui.feature.guardian.view.GuardianWaitSlot
import com.violinstudio.ui.feature.home.view.HomeRoute
import com.violinstudio.ui.feature.onboarding.view.OnboardingRoute
import com.violinstudio.ui.feature.session.view.OfflineScreen
import com.violinstudio.ui.feature.session.view.SplashScreen
import com.violinstudio.ui.feature.session.viewmodel.SessionIntent
import com.violinstudio.ui.feature.session.viewmodel.SessionViewModel
import com.violinstudio.ui.feature.settings.view.SettingsRoute

@Composable
fun AppNavHost(viewModel: SessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionNavHost(
        session = state.session,
        onSignOut = { viewModel.onIntent(SessionIntent.SignOut) },
        deleteViewModel = { hiltViewModel() }
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
    home: @Composable (onOpenSettings: () -> Unit) -> Unit = { HomeRoute(onOpenSettings = it) },
    auth: @Composable () -> Unit = { AuthRoute() },
    verifyEmail: @Composable (email: String?) -> Unit = { VerifyEmailRoute(it) },
    onboarding: @Composable () -> Unit = { OnboardingRoute() },
    consent: @Composable (SessionState.ConsentPending) -> Unit = { ConsentSlot(it) },
    guardianWait: @Composable (SessionState.ParentalPending) -> Unit = { GuardianWaitSlot(it) },
    settings: @Composable (onBack: () -> Unit) -> Unit = { SettingsRoute(onBack = it) },
    /** ViewModel del borrado compartido de cada destino que lo ofrece (D1); nulo: sin borrado (tests, capturas). */
    deleteViewModel: (@Composable () -> DeleteAccountViewModel)? = null
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
    // Un único Surface con el fondo del tema para todas las raíces (splash, offline, marcadores...): sin él el texto
    // toma el color por defecto y queda oscuro sobre oscuro.
    Surface(Modifier.fillMaxSize()) {
        Box {
            SessionGraph(
                routed, navController, onSignOut, home, auth, verifyEmail, onboarding, consent, guardianWait, settings,
                deleteViewModel
            )
            if (session is SessionState.Unavailable && routed is SessionState.Ready) {
                Surface(Modifier.fillMaxSize()) { OfflineScreen(onSignOut) }
            }
        }
    }
}

@Composable
private fun SessionGraph(
    session: SessionState,
    navController: NavHostController,
    onSignOut: () -> Unit,
    home: @Composable (onOpenSettings: () -> Unit) -> Unit,
    auth: @Composable () -> Unit,
    verifyEmail: @Composable (email: String?) -> Unit,
    onboarding: @Composable () -> Unit,
    consent: @Composable (SessionState.ConsentPending) -> Unit,
    guardianWait: @Composable (SessionState.ParentalPending) -> Unit,
    settings: @Composable (onBack: () -> Unit) -> Unit,
    deleteViewModel: (@Composable () -> DeleteAccountViewModel)?
) {
    // El email es el del último EmailUnverified: durante la transición de salida la sesión ya es otra y el slot no
    // debe quedarse sin él.
    val lastEmail = rememberLastEmail(session)
    val lastConsent = rememberLastConsent(session)
    val lastWait = rememberLastGuardianWait(session, navController)
    NavHost(navController = navController, startDestination = SplashDestination) {
        composable<SplashDestination> { SplashScreen() }
        composable<OfflineDestination> { OfflineScreen(onSignOut) }
        composable<AuthDestination> { auth() }
        composable<VerifyEmailDestination> { WithDeleteAccount(deleteViewModel) { verifyEmail(lastEmail) } }
        composable<OnboardingDestination> { WithDeleteAccount(deleteViewModel) { onboarding() } }
        composable<ConsentDestination> { WithDeleteAccount(deleteViewModel) { lastConsent?.let { consent(it) } } }
        composable<GuardianWaitDestination> {
            WithDeleteAccount(deleteViewModel) { lastWait?.let { guardianWait(it) } }
        }
        // Defensa en profundidad: aunque un back stack restaurado o un enlace caiga aquí sin Ready, no se compone
        // contenido de negocio mientras la redirección está en curso.
        composable<HomeDestination> {
            if (session is SessionState.Ready) {
                home { navController.navigate(SettingsDestination) { launchSingleTop = true } }
            } else {
                SplashScreen()
            }
        }
        composable<SettingsDestination> {
            if (session is SessionState.Ready) {
                WithDeleteAccount(deleteViewModel) { settings { navController.popBackStack() } }
            } else {
                SplashScreen()
            }
        }
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
        // Ajustes es una ruta de negocio más: solo se mantiene con Ready.
        val onSettings = session is SessionState.Ready &&
            current?.destination?.hasRoute(SettingsDestination::class) == true
        if (!onSettings && current?.destination?.hasRoute(target::class) != true) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}

// Como el email: durante la transición de salida la sesión ya es otra y el slot no debe quedarse sin su dato.
@Composable
private fun rememberLastConsent(session: SessionState): SessionState.ConsentPending? {
    val holder = remember { arrayOfNulls<SessionState.ConsentPending>(1) }
    (session as? SessionState.ConsentPending)?.let { holder[0] = it }
    return holder[0]
}

@Composable
private fun rememberLastGuardianWait(
    session: SessionState,
    navController: NavHostController
): SessionState.ParentalPending? {
    val current by navController.currentBackStackEntryAsState()
    val holder = remember { arrayOfNulls<SessionState.ParentalPending>(1) }
    if (session is SessionState.ParentalPending) {
        holder[0] = session
    } else if (current?.destination?.hasRoute(GuardianWaitDestination::class) != true) {
        // Ya fuera de la espera (y sin transición de salida en curso): el dato viejo no puede volver a entregarse.
        holder[0] = null
    }
    return holder[0]
}
