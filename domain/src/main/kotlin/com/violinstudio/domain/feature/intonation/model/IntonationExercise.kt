package com.violinstudio.domain.feature.intonation.model

import com.violinstudio.domain.feature.tuner.model.Note

/**
 * Ejercicio de afinacion: [notes] en orden, cada una distinta de la anterior (el seguimiento avanza al reconocer la
 * siguiente). [id] es estable (clave de la mejor puntuacion guardada): no se renombra.
 */
data class IntonationExercise(val id: String, val lessonId: String, val notes: List<Note>) {
    init {
        require(notes.isNotEmpty()) { "ejercicio vacio" }
        require(notes.zipWithNext().none { (a, b) -> a == b }) { "dos notas iguales seguidas" }
    }
}

/** Ejercicios de la app (violin, en primera posicion salvo `i3`): escala de subida y bajada. */
object IntonationExercises {
    val all: List<IntonationExercise> = listOf(
        exercise("a_string_pattern", "e1_finger_pattern", 69, 71, 73, 74),
        exercise("d_major_one_octave", "e2_d_major_scale", 62, 64, 66, 67, 69, 71, 73, 74),
        exercise(
            "g_major_two_octaves",
            "i1_g_major_two_octaves",
            55, 57, 59, 60, 62, 64, 66, 67, 69, 71, 72, 74, 76, 78, 79
        ),
        exercise("a_string_third_position", "i3_third_position", 74, 76, 78, 79)
    )

    private val idIndex = all.associateBy { it.id }
    private val lessonIndex = all.associateBy { it.lessonId }

    fun byId(id: String): IntonationExercise? = idIndex[id]

    fun forLesson(lessonId: String): IntonationExercise? = lessonIndex[lessonId]

    /** Sube por [up] y baja sin repetir la nota de arriba. */
    private fun exercise(id: String, lessonId: String, vararg up: Int): IntonationExercise {
        val notes = up.toList() + up.toList().dropLast(1).reversed()
        return IntonationExercise(id, lessonId, notes.map(::Note))
    }
}
