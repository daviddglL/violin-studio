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

    /** [isMinor] lo fija el servidor: un menor necesita a su tutor en vez de aceptar él mismo. */
    data class ConsentPending(val config: IdentityConfig, val isMinor: Boolean) : SessionState

    data class ParentalPending(val emailMasked: String?, val sends: Int) : SessionState {
        override fun toString(): String = "ParentalPending(sends=$sends)"
    }

    data class Ready(val profile: UserProfile) : SessionState
}
