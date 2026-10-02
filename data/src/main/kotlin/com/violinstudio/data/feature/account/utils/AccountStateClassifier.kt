package com.violinstudio.data.feature.account.utils

import com.google.firebase.auth.FirebaseAuthException

/** Lo que el SDK de Auth sabe de la cuenta tras un `currentUser.reload()`. */
enum class AccountState { EXISTS, GONE, UNKNOWN }

/**
 * Decide si la cuenta sigue existiendo a partir del error (o la ausencia de error) de `reload()`. Solo estos códigos
 * prueban que ya no hay cuenta utilizable: usuario inexistente, deshabilitado o token revocado. `USER_DISABLED` es lo
 * que deja un borrado a medias (el paso "mark" deshabilita al usuario) y la purga (7b.3) lo termina, así que cuenta
 * como desaparecida. Red, falta de sesión u otros errores no prueban nada: `UNKNOWN`.
 */
object AccountStateClassifier {
    private val GONE_CODES = setOf("ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED", "ERROR_USER_TOKEN_EXPIRED")

    fun classify(reloadError: Throwable?): AccountState = when {
        reloadError == null -> AccountState.EXISTS
        reloadError is FirebaseAuthException && reloadError.errorCode in GONE_CODES -> AccountState.GONE
        else -> AccountState.UNKNOWN
    }
}
