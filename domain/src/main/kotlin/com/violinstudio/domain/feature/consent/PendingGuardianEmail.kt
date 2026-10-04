package com.violinstudio.domain.feature.consent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Último email de tutor que el servidor aceptó, solo en memoria: la app nunca lo persiste ni lo registra. Permite
 * "Reenviar" sin volver a pedirlo; tras morir el proceso queda vacío y solo cabe "Cambiar email". Se vacía al cerrar
 * sesión (lo hace [com.violinstudio.domain.feature.session.usecase.ObserveSessionStateUseCase]).
 */
@Singleton
class PendingGuardianEmail @Inject constructor() {
    @Volatile
    var email: String? = null
        private set

    fun remember(email: String) {
        this.email = email
    }

    fun clear() {
        email = null
    }

    override fun toString(): String = "PendingGuardianEmail(present=${email != null})"
}
