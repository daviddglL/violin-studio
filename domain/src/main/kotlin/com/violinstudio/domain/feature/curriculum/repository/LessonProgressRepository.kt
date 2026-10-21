package com.violinstudio.domain.feature.curriculum.repository

import kotlinx.coroutines.flow.Flow

/**
 * Lecciones marcadas como hechas, guardadas solo en el dispositivo y por usuario (se borran con la cuenta). Leer nunca
 * falla: un error es "nada hecho". Escribir falla con `CurriculumFailure.Storage`.
 */
interface LessonProgressRepository {
    fun observeCompleted(uid: String): Flow<Set<String>>

    suspend fun setCompleted(uid: String, lessonId: String, done: Boolean)
}
