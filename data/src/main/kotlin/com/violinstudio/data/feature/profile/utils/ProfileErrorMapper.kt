package com.violinstudio.data.feature.profile.utils

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.violinstudio.domain.feature.profile.failure.ProfileFailure
import java.io.IOException

object ProfileErrorMapper {
    /**
     * Fallo del listener de `users/{uid}`. `permission-denied` y `not-found` significan que no hay un perfil
     * legible (p. ej. cuenta recién borrada): `NoProfile`, que la sesión trata como perfil ausente.
     */
    fun fromListener(error: Throwable): ProfileFailure = when {
        error is FirebaseFirestoreException && error.code in NO_PROFILE_CODES -> ProfileFailure.NoProfile
        else -> common(error)
    }

    /** Fallo de la escritura directa en Firestore (edición de los tres campos). */
    fun fromUpdate(error: Throwable): ProfileFailure = when {
        error is FirebaseFirestoreException && error.code == Code.NOT_FOUND -> ProfileFailure.NoProfile
        // Las reglas rechazaron: consentimiento no concedido, borrado en curso o claim obsoleto.
        error is FirebaseFirestoreException && error.code == Code.PERMISSION_DENIED -> ProfileFailure.NotAllowed
        else -> common(error)
    }

    private fun common(error: Throwable): ProfileFailure = when {
        error is IOException -> ProfileFailure.Network
        error is FirebaseFirestoreException && error.code in OFFLINE_CODES -> ProfileFailure.Network
        else -> ProfileFailure.Unknown(error)
    }

    private val NO_PROFILE_CODES = setOf(Code.PERMISSION_DENIED, Code.NOT_FOUND)
    private val OFFLINE_CODES = setOf(Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED)
}
