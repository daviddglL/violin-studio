package com.violinstudio.domain.feature.curriculum.model

/** Niveles del curriculo, en orden de estudio. */
enum class Level { BEGINNER, ELEMENTARY, INTERMEDIATE }

/** Herramienta de la app con la que se practica la leccion; la UI ofrece abrirla. */
sealed interface LessonTool {
    data object None : LessonTool

    data object Tuner : LessonTool

    /** Metronomo al tempo sugerido. */
    data class Metronome(val bpm: Int) : LessonTool
}

/**
 * Leccion del curriculo. [id] es estable (es la clave del progreso guardado y de los textos en la UI): no se renombra.
 * [minutes] es la practica diaria sugerida.
 */
data class Lesson(val id: String, val level: Level, val minutes: Int, val tool: LessonTool)

/** Curriculo fijo de la app: 4 lecciones por nivel, en orden de estudio. Los textos viven en la UI (es/en). */
object Curriculum {
    val lessons: List<Lesson> = listOf(
        Lesson("b1_posture", Level.BEGINNER, 10, LessonTool.None),
        Lesson("b2_bow_hold", Level.BEGINNER, 10, LessonTool.None),
        Lesson("b3_open_strings", Level.BEGINNER, 15, LessonTool.Metronome(60)),
        Lesson("b4_tuning", Level.BEGINNER, 10, LessonTool.Tuner),
        Lesson("e1_finger_pattern", Level.ELEMENTARY, 15, LessonTool.Tuner),
        Lesson("e2_d_major_scale", Level.ELEMENTARY, 15, LessonTool.Metronome(60)),
        Lesson("e3_bow_distribution", Level.ELEMENTARY, 15, LessonTool.Metronome(60)),
        Lesson("e4_string_crossing", Level.ELEMENTARY, 15, LessonTool.Metronome(72)),
        Lesson("i1_g_major_two_octaves", Level.INTERMEDIATE, 20, LessonTool.Metronome(72)),
        Lesson("i2_dynamics", Level.INTERMEDIATE, 15, LessonTool.None),
        Lesson("i3_third_position", Level.INTERMEDIATE, 20, LessonTool.Tuner),
        Lesson("i4_vibrato", Level.INTERMEDIATE, 15, LessonTool.Metronome(60))
    )

    private val ids: Set<String> = lessons.map { it.id }.toSet()

    fun contains(id: String): Boolean = id in ids
}

/** Avance de un nivel: sus lecciones en orden y cuantas estan hechas. */
data class LevelProgress(val level: Level, val lessons: List<LessonProgress>) {
    val done: Int get() = lessons.count { it.done }
    val total: Int get() = lessons.size
}

data class LessonProgress(val lesson: Lesson, val done: Boolean)

/** Curriculo con el progreso del usuario; [next] es la primera leccion sin hacer en orden de estudio. */
data class CurriculumProgress(val levels: List<LevelProgress>) {
    val next: Lesson? get() = levels.asSequence().flatMap { it.lessons }.firstOrNull { !it.done }?.lesson
    val done: Int get() = levels.sumOf { it.done }
    val total: Int get() = levels.sumOf { it.total }

    companion object {
        /** Ids desconocidos (lecciones retiradas) se ignoran. */
        fun of(completed: Set<String>): CurriculumProgress = CurriculumProgress(
            Level.entries.map { level ->
                LevelProgress(
                    level,
                    Curriculum.lessons.filter { it.level == level }.map { LessonProgress(it, it.id in completed) }
                )
            }
        )
    }
}
