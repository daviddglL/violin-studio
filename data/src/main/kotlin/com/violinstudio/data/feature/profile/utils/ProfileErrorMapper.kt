package com.violinstudio.data.feature.profile.utils

import com.violinstudio.domain.feature.profile.failure.ProfileFailure

object ProfileErrorMapper {
    /** Fallo del listener de `users/{uid}`. */
    fun fromListener(error: Throwable): ProfileFailure = TODO()

    /** Fallo de la escritura directa en Firestore (edición de los tres campos). */
    fun fromUpdate(error: Throwable): ProfileFailure = TODO()
}
