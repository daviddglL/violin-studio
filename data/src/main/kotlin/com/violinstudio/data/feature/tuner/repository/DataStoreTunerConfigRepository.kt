package com.violinstudio.data.feature.tuner.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.violinstudio.data.feature.tuner.utils.TunerConfigParser
import com.violinstudio.data.feature.tuner.utils.UserKeys
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreTunerConfigRepository @Inject constructor(
    private val store: DataStore<Preferences>
) : TunerConfigRepository {
    override fun observe(uid: String): Flow<TunerConfig> =
        store.data.map { TunerConfigParser.read(it, uid) }.distinctUntilChanged()

    override suspend fun update(uid: String, transform: (TunerConfig) -> TunerConfig) {
        store.edit { TunerConfigParser.write(it, uid, transform(TunerConfigParser.read(it, uid))) }
    }

    override suspend fun clear(uid: String) {
        store.edit { prefs -> prefs.asMap().keys.filter { UserKeys.belongsTo(it, uid) }.forEach { prefs.remove(it) } }
    }
}
