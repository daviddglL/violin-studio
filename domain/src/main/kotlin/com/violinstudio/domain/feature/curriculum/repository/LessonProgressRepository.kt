package com.violinstudio.domain.feature.curriculum.repository

import kotlinx.coroutines.flow.Flow

/**
 * Lecciones marcadas como hechas, por usuario y en la nube (se borran con la cuenta); el profesor vinculado puede leer
 * las de su alumno. Leer nunca falla: un error (sin conexion previa, reglas que deniegan) es "nada hecho". Escribir
 * falla con `CurriculumFailure.Storage`.
 */
interface LessonProgressRepository {
    fun observeCompleted(uid: String): Flow<Set<String>>

    suspend fun setCompleted(uid: String, lessonId: String, done: Boolean)
}
