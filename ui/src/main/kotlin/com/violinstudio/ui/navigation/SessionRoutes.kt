package com.violinstudio.ui.navigation

import com.violinstudio.domain.feature.session.SessionState
import kotlinx.serialization.Serializable

@Serializable
data object SplashDestination

@Serializable
data object AuthDestination

@Serializable
data object VerifyEmailDestination

@Serializable
data object OnboardingDestination

@Serializable
data object ConsentDestination

@Serializable
data object GuardianWaitDestination

/** Sin red y reintentando: no es terminal, la sesión lo sustituye sola cuando vuelve a resolverse. */
@Serializable
data object OfflineDestination

@Serializable
data object HomeDestination

/** Ruta de negocio dentro del grafo de Ready: se mantiene solo con Ready y cae a la raíz del estado si cambia. */
@Serializable
data object SettingsDestination

@Serializable
data object TunerDestination

@Serializable
data object MetronomeDestination

/** Rutas de negocio: solo se mantienen con Ready; con otro estado SessionRedirect las lleva a la raíz. */
val BUSINESS_ROUTES: List<kotlin.reflect.KClass<*>> =
    listOf(SettingsDestination::class, TunerDestination::class, MetronomeDestination::class)

/** Ruta raíz que corresponde a cada estado de sesión: solo `Ready` llega a rutas de negocio. */
fun SessionState.rootRoute(): Any = when (this) {
    SessionState.Loading -> SplashDestination
    SessionState.Unavailable -> OfflineDestination
    SessionState.LoggedOut -> AuthDestination
    is SessionState.EmailUnverified -> VerifyEmailDestination
    SessionState.NeedsProfile -> OnboardingDestination
    is SessionState.ConsentPending -> ConsentDestination
    is SessionState.ParentalPending -> GuardianWaitDestination
    is SessionState.Ready -> HomeDestination
}
