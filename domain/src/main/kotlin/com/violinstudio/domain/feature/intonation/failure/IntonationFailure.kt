package com.violinstudio.domain.feature.intonation.failure

sealed class IntonationFailure(message: String) : Exception(message) {
    /** Fallos esperables sin traza de pila: son valores, no errores de programa. */
    override fun fillInStackTrace(): Throwable = this

    data object NoSession : IntonationFailure("Sin sesión")
    data object UnknownExercise : IntonationFailure("Ejercicio desconocido")

    /** No se pudo guardar en el dispositivo. */
    data object Storage : IntonationFailure("No se pudo guardar la puntuación")
}
