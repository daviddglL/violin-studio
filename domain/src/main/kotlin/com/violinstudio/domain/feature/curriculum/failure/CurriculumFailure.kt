package com.violinstudio.domain.feature.curriculum.failure

sealed class CurriculumFailure(message: String) : Exception(message) {
    /** Fallos esperables sin traza de pila: son valores, no errores de programa. */
    override fun fillInStackTrace(): Throwable = this

    data object NoSession : CurriculumFailure("Sin sesión")
    data object UnknownLesson : CurriculumFailure("Lección desconocida")

    /** No se pudo guardar en el dispositivo. */
    data object Storage : CurriculumFailure("No se pudo guardar el progreso")
}
