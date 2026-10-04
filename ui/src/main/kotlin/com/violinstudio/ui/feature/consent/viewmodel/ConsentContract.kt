package com.violinstudio.ui.feature.consent.viewmodel

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import java.net.URI
import java.net.URISyntaxException

/** Fallos de la aceptación. La versión y la legalidad las decide el servidor; aquí solo se informa. */
enum class ConsentError {
    NETWORK,

    /** La política cambió mientras el usuario leía: se recargó la versión nueva y hay que aceptarla de nuevo. */
    POLICY_CHANGED,

    /** Cambió la versión pero no se pudo recargar la política. */
    POLICY_UNAVAILABLE,

    /** El servidor dice que esta cuenta necesita a su tutor (el flujo del menor es de 6b). */
    GUARDIAN_REQUIRED,
    UNKNOWN
}

/** Solo se abre un enlace `https` con host y sin credenciales incrustadas: nada de `http`, `intent:` o `file:`. */
fun isSafePolicyUrl(url: String): Boolean = try {
    val uri = URI(url.trim())
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: URISyntaxException) {
    false
}

data class ConsentState(
    /** La política vigente: la de la sesión o, tras `PolicyOutdated`, la recargada. `null` hasta recibir la sesión. */
    val config: IdentityConfig? = null,
    val reason: ConsentReason = ConsentReason.FIRST,
    val checked: Boolean = false,
    val isLoading: Boolean = false,
    val error: ConsentError? = null,
    val policyLinkFailed: Boolean = false,
    val succeeded: Boolean = false
) : UiState {
    val policyVersion: Int? get() = config?.policyVersion

    /** Tras aceptar, el botón sigue bloqueado: la sesión sustituirá la pantalla. */
    val canAccept: Boolean
        get() = config != null && checked && !isLoading && !succeeded
}

sealed interface ConsentIntent : UiIntent {
    /** La sesión (que manda) pide aceptar esta política por este motivo; llega al abrir y en cada bump en caliente. */
    data class SessionUpdated(val config: IdentityConfig, val reason: ConsentReason) : ConsentIntent
    data class CheckedChanged(val checked: Boolean) : ConsentIntent
    data object OpenPolicy : ConsentIntent

    /** La Route no encontró app para abrir el enlace. */
    data object PolicyLinkFailed : ConsentIntent
    data object Accept : ConsentIntent
    data object SignOut : ConsentIntent
}

sealed interface ConsentEffect : UiEffect {
    /** Abrir la política en el navegador; la URL es la del servidor y ya está validada como `https`. */
    data class OpenPolicy(val url: String) : ConsentEffect
}

sealed interface ConsentMutation {
    data class SessionUpdated(val config: IdentityConfig, val reason: ConsentReason) : ConsentMutation
    data class CheckedChanged(val checked: Boolean) : ConsentMutation
    data object OpenPolicyRequested : ConsentMutation
    data object PolicyLinkFailed : ConsentMutation
    data object AcceptRequested : ConsentMutation
    data object Succeeded : ConsentMutation
    data class PolicyOutdated(val config: IdentityConfig) : ConsentMutation
    data class Failed(val error: ConsentError) : ConsentMutation
}
