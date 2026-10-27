package com.violinstudio.domain.feature.session

import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.profile.model.UserProfile

/** Estado de sesión del que depende toda la navegación. Solo `Ready` da acceso a rutas de negocio. */
sealed interface SessionState {
    /** Resolviendo la sesión (arranque, o borrado de cuenta en curso). */
    data object Loading : SessionState

    /** No se pudo obtener la configuración o el perfil (sin red...); se reintenta solo con espera exponencial. */
    data object Unavailable : SessionState

    data object LoggedOut : SessionState

    data class EmailUnverified(val email: String?) : SessionState {
        override fun toString(): String = "EmailUnverified"
    }

    data object NeedsProfile : SessionState

    /** La app es mas antigua que la version minima del servidor: solo se puede actualizar o cerrar sesion. */
    data object UpdateRequired : SessionState

    /** [isMinor] lo fija el servidor: un menor necesita a su tutor en vez de aceptar él mismo. */
    data class ConsentPending(
        val config: IdentityConfig,
        val isMinor: Boolean,
        val reason: ConsentReason = ConsentReason.FIRST
    ) : SessionState

    data class ParentalPending(val emailMasked: String?, val sends: Int) : SessionState {
        override fun toString(): String = "ParentalPending(sends=$sends)"
    }

    /** [features]: interruptores del servidor que deciden qué accesos se muestran (apagados por defecto). */
    data class Ready(val profile: UserProfile, val features: SessionFeatures = SessionFeatures()) : SessionState
}

/** Funciones que el servidor tiene encendidas ([IdentityConfig]): con una apagada, su acceso no se muestra. */
data class SessionFeatures(
    val recordings: Boolean = false,
    val chat: Boolean = false,
    val notifications: Boolean = false
) {
    companion object {
        fun from(config: IdentityConfig) = SessionFeatures(
            recordings = config.recordingsEnabled,
            chat = config.chatEnabled,
            notifications = config.notificationsEnabled
        )
    }
}

/** Por qué se pide (re)aceptar: la UI cambia el texto, nunca la lógica. */
enum class ConsentReason {
    /** Primer consentimiento (o estado desconocido, fail-closed). */
    FIRST,

    /** Tenía un consentimiento `granted` de otra versión de la política. */
    POLICY_UPDATED,

    /** El usuario retiró su consentimiento. */
    REVOKED
}
