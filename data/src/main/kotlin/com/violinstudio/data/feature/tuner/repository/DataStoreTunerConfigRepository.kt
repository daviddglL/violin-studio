package com.violinstudio.data.feature.tuner.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.feature.tuner.utils.TunerConfigParser
import com.violinstudio.data.feature.tuner.utils.UserKeys
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los fallos de E-S al leer se tratan como "sin datos" y al escribir o borrar se traducen a
 * [TunerFailure.StorageUnavailable], para que el dominio no conozca `java.io` y el afinador nunca caiga por el disco.
 */
@Singleton
class DataStoreTunerConfigRepository @Inject constructor(
    private val store: DataStore<Preferences>
) : TunerConfigRepository {
    override fun observe(uid: String): Flow<TunerConfig> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { TunerConfigParser.read(it, uid) }
        .distinctUntilChanged()

    override suspend fun update(uid: String, transform: (TunerConfig) -> TunerConfig) = guarded {
        store.edit { prefs ->
            if (TunerConfigParser.isNewerSchema(prefs, uid)) throw TunerFailure.StorageUnavailable
            TunerConfigParser.write(prefs, uid, transform(TunerConfigParser.read(prefs, uid)))
        }
    }

    override suspend fun clear(uid: String) = guarded {
        store.edit { prefs -> prefs.asMap().keys.filter { UserKeys.belongsTo(it, uid) }.forEach { prefs.remove(it) } }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            throw TunerFailure.StorageUnavailable
        }
    }
}
