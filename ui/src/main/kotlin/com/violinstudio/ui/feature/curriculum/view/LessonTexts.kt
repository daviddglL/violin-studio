package com.violinstudio.ui.feature.curriculum.view

import androidx.annotation.StringRes
import com.violinstudio.domain.feature.curriculum.model.Level
import com.violinstudio.ui.R

/** Textos de una leccion: titulo, objetivo y pasos (una linea por paso, separadas por " · "). */
data class LessonTexts(@StringRes val title: Int, @StringRes val goal: Int, @StringRes val steps: Int)

/** `null` para un id que la UI no conoce: la leccion no se muestra. Un test exige textos para todo el curriculo. */
fun lessonTexts(id: String): LessonTexts? = when (id) {
    "b1_posture" -> LessonTexts(R.string.lesson_b1_title, R.string.lesson_b1_goal, R.string.lesson_b1_steps)

    "b2_bow_hold" -> LessonTexts(R.string.lesson_b2_title, R.string.lesson_b2_goal, R.string.lesson_b2_steps)

    "b3_open_strings" -> LessonTexts(R.string.lesson_b3_title, R.string.lesson_b3_goal, R.string.lesson_b3_steps)

    "b4_tuning" -> LessonTexts(R.string.lesson_b4_title, R.string.lesson_b4_goal, R.string.lesson_b4_steps)

    "e1_finger_pattern" -> LessonTexts(R.string.lesson_e1_title, R.string.lesson_e1_goal, R.string.lesson_e1_steps)

    "e2_d_major_scale" -> LessonTexts(R.string.lesson_e2_title, R.string.lesson_e2_goal, R.string.lesson_e2_steps)

    "e3_bow_distribution" -> LessonTexts(R.string.lesson_e3_title, R.string.lesson_e3_goal, R.string.lesson_e3_steps)

    "e4_string_crossing" -> LessonTexts(R.string.lesson_e4_title, R.string.lesson_e4_goal, R.string.lesson_e4_steps)

    "i1_g_major_two_octaves" ->
        LessonTexts(R.string.lesson_i1_title, R.string.lesson_i1_goal, R.string.lesson_i1_steps)

    "i2_dynamics" -> LessonTexts(R.string.lesson_i2_title, R.string.lesson_i2_goal, R.string.lesson_i2_steps)

    "i3_third_position" -> LessonTexts(R.string.lesson_i3_title, R.string.lesson_i3_goal, R.string.lesson_i3_steps)

    "i4_vibrato" -> LessonTexts(R.string.lesson_i4_title, R.string.lesson_i4_goal, R.string.lesson_i4_steps)

    else -> null
}

@StringRes
fun levelTitle(level: Level): Int = when (level) {
    Level.BEGINNER -> R.string.curriculum_level_beginner
    Level.ELEMENTARY -> R.string.curriculum_level_elementary
    Level.INTERMEDIATE -> R.string.curriculum_level_intermediate
}
