package com.violinstudio.data.feature.curriculum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.domain.feature.curriculum.failure.CurriculumFailure
import com.violinstudio.domain.feature.curriculum.repository.LessonProgressRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lecciones hechas en el DataStore por usuario (`u.{uid}.curriculum.completed`), solo en el dispositivo: el eraser de
 * datos locales las borra con el resto de claves del uid. Leer nunca falla: un error de E-S o un valor de otro tipo es
 * "nada hecho". Escribir traduce el error de E-S a [CurriculumFailure.Storage].
 */
@Singleton
class DataStoreLessonProgressRepository @Inject constructor(private val store: DataStore<Preferences>) :
    LessonProgressRepository {
    override fun observeCompleted(uid: String): Flow<Set<String>> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it, uid) }
        .distinctUntilChanged()

    override suspend fun setCompleted(uid: String, lessonId: String, done: Boolean) {
        try {
            store.edit { prefs ->
                val key = UserKeys.completedLessons(uid)
                val current = read(prefs, uid)
                val next = if (done) current + lessonId else current - lessonId
                if (next.isEmpty()) prefs.remove(key) else prefs[key] = next
            }
        } catch (_: IOException) {
            throw CurriculumFailure.Storage
        }
    }

    // `prefs[key]` lanza ClassCastException si el valor guardado es de otro tipo: se lee por nombre y con `as?`.
    private fun read(prefs: Preferences, uid: String): Set<String> {
        val value = prefs.asMap().entries.firstOrNull { it.key.name == UserKeys.completedLessons(uid).name }?.value
        return (value as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
    }
}
