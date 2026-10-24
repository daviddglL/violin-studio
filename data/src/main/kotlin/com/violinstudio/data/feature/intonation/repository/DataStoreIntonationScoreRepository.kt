package com.violinstudio.data.feature.intonation.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.domain.feature.intonation.failure.IntonationFailure
import com.violinstudio.domain.feature.intonation.repository.IntonationScoreRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Mejores puntuaciones en el DataStore del dispositivo, bajo `u.{uid}.intonation.best.*` (el eraser de datos locales
 * las borra con el resto del usuario). Leer con fallo de E-S da "sin puntuaciones"; valores de otro tipo se ignoran.
 */
@Singleton
class DataStoreIntonationScoreRepository @Inject constructor(
    private val store: DataStore<Preferences>
) : IntonationScoreRepository {
    override fun observeBest(uid: String): Flow<Map<String, Int>> {
        val prefix = UserKeys.intonationBestPrefix(uid)
        return store.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { prefs ->
                prefs.asMap().entries
                    .filter { (key, value) -> key.name.startsWith(prefix) && value is Int }
                    .associate { (key, value) -> key.name.removePrefix(prefix) to value as Int }
            }
            .distinctUntilChanged()
    }

    override suspend fun saveIfBest(uid: String, exerciseId: String, score: Int) {
        val key = UserKeys.intonationBest(uid, exerciseId)
        try {
            store.edit { prefs -> if (score > (prefs[key] ?: -1)) prefs[key] = score }
        } catch (_: IOException) {
            throw IntonationFailure.Storage
        }
    }
}
