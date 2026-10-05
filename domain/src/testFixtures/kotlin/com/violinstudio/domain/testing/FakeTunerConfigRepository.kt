package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.repository.TunerConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Repositorio en memoria por uid; `cleared` registra los uid borrados. */
class FakeTunerConfigRepository : TunerConfigRepository {
    private val store = MutableStateFlow<Map<String, TunerConfig>>(emptyMap())
    val cleared = mutableListOf<String>()

    override fun observe(uid: String): Flow<TunerConfig> = store.map { it[uid] ?: TunerConfig() }

    override suspend fun update(uid: String, transform: (TunerConfig) -> TunerConfig) {
        store.value = store.value + (uid to transform(store.value[uid] ?: TunerConfig()))
    }

    override suspend fun clear(uid: String) {
        cleared += uid
        store.value = store.value - uid
    }
}
