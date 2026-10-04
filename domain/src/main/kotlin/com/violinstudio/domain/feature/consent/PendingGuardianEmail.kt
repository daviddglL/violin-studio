package com.violinstudio.domain.feature.consent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultimo email de tutor que el servidor acepto, solo en memoria y atado al usuario que lo pidio: la app nunca lo
 * persiste ni lo registra. Permite "Reenviar" sin volver a pedirlo; tras morir el proceso queda vacio y solo cabe
 * "Cambiar email". Lo vacia [retainOnlyFor] cuando la sesion pasa a otro usuario o a ninguno.
 */
@Singleton
class PendingGuardianEmail @Inject constructor() {
    private data class Entry(val uid: String, val email: String)

    @Volatile
    private var entry: Entry? = null

    fun remember(uid: String, email: String) {
        entry = Entry(uid, email)
    }

    fun emailFor(uid: String): String? = entry?.takeIf { it.uid == uid }?.email

    /** Conserva el email solo si es del usuario [uid]; `null` (sin sesion) lo borra. */
    fun retainOnlyFor(uid: String?) {
        if (entry?.uid != uid) entry = null
    }

    override fun toString(): String = "PendingGuardianEmail(present=${entry != null})"
}
