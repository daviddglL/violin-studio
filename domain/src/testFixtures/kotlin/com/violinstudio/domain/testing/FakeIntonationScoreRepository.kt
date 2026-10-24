package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.intonation.repository.IntonationScoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Mejores puntuaciones en memoria por uid; `calls` registra cada interaccion. */
class FakeIntonationScoreRepository : IntonationScoreRepository {
    private val store = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val calls = mutableListOf<String>()

    /** Si no es `null`, `saveIfBest` lanza esto. */
    var writeFailure: Exception? = null

    fun seed(uid: String, vararg scores: Pair<String, Int>) {
        store.value = store.value + (uid to scores.toMap())
    }

    override fun observeBest(uid: String): Flow<Map<String, Int>> {
        calls += "observe:$uid"
        return store.map { it[uid].orEmpty() }
    }

    override suspend fun saveIfBest(uid: String, exerciseId: String, score: Int) {
        calls += "save:$uid:$exerciseId:$score"
        writeFailure?.let { throw it }
        val current = store.value[uid].orEmpty()
        if (score > (current[exerciseId] ?: -1)) store.value = store.value + (uid to current + (exerciseId to score))
    }
}
