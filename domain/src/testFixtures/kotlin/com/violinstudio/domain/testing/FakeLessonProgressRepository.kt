package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.curriculum.repository.LessonProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Progreso en memoria por uid; `calls` registra cada interaccion. */
class FakeLessonProgressRepository : LessonProgressRepository {
    private val store = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val calls = mutableListOf<String>()

    /** Si no es `null`, `setCompleted` lanza esto. */
    var writeFailure: Exception? = null

    fun seed(uid: String, vararg ids: String) {
        store.value = store.value + (uid to ids.toSet())
    }

    override fun observeCompleted(uid: String): Flow<Set<String>> {
        calls += "observe:$uid"
        return store.map { it[uid].orEmpty() }
    }

    override suspend fun setCompleted(uid: String, lessonId: String, done: Boolean) {
        calls += "set:$uid:$lessonId:$done"
        writeFailure?.let { throw it }
        val current = store.value[uid].orEmpty()
        store.value = store.value + (uid to if (done) current + lessonId else current - lessonId)
    }
}
