package com.violinstudio.data.feature.curriculum.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.commons.datastore.UserKeys
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * Progreso que las versiones anteriores guardaban solo en el dispositivo (`u.{uid}.curriculum.completed`). Solo se lee
 * para subirlo a la nube una vez y se borra cuando el servidor lo confirma. Leer nunca falla: un error de E-S o un
 * valor de otro tipo es "nada guardado".
 */
@Singleton
class LegacyLessonProgressStore @Inject constructor(private val store: DataStore<Preferences>) {
    suspend fun read(uid: String): Set<String> = read(
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first(),
        uid
    )

    suspend fun clear(uid: String) {
        store.edit { it.remove(UserKeys.completedLessons(uid)) }
    }

    // `prefs[key]` lanza ClassCastException si el valor guardado es de otro tipo: se lee por nombre y con `as?`.
    private fun read(prefs: Preferences, uid: String): Set<String> {
        val value = prefs.asMap().entries.firstOrNull { it.key.name == UserKeys.completedLessons(uid).name }?.value
        return (value as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
    }
}
