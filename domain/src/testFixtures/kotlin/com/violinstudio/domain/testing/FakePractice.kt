package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeDraft
import com.violinstudio.domain.feature.practice.model.PracticeSession
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.practice.repository.PracticeLogRepository
import com.violinstudio.domain.feature.practice.repository.RunningSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Historial en memoria por uid; `created` registra cada escritura y `calls` toda interaccion. */
class FakePracticeLogRepository : PracticeLogRepository {
    private val store = MutableStateFlow<Map<String, List<PracticeSession>>>(emptyMap())
    val calls = mutableListOf<String>()
    val created = mutableListOf<Pair<String, PracticeDraft>>()
    var createFailure: PracticeFailure? = null
    var lastLimit: Int? = null

    /** Se ejecuta dentro de `create`, antes de escribir: permite forzar intercalado de corrutinas. */
    var onCreate: suspend () -> Unit = {}

    fun seed(uid: String, vararg sessions: PracticeSession) {
        store.value = store.value + (uid to sessions.toList())
    }

    override fun observeHistory(uid: String, limit: Int): Flow<List<PracticeSession>> {
        calls += "observe:$uid"
        lastLimit = limit
        return store.map { it[uid].orEmpty() }
    }

    override suspend fun create(uid: String, draft: PracticeDraft): Result<Unit> {
        calls += "create:$uid"
        onCreate()
        createFailure?.let { return Result.failure(it) }
        created += uid to draft
        val saved = PracticeSession(draft.id, draft.startedAt, draft.durationSec, draft.instrument, draft.notes, true)
        store.value = store.value + (uid to (store.value[uid].orEmpty() + saved))
        return Result.success(Unit)
    }

    override suspend fun exists(uid: String, id: String): Boolean {
        calls += "exists:$uid:$id"
        return store.value[uid].orEmpty().any { it.id == id }
    }

    override suspend fun updateNotes(uid: String, id: String, notes: String?): Result<Unit> {
        calls += "updateNotes:$uid:$id:$notes"
        return Result.success(Unit)
    }

    override suspend fun delete(uid: String, id: String): Result<Unit> {
        calls += "delete:$uid:$id"
        return Result.success(Unit)
    }
}

/** Almacen en memoria por uid: reutilizarlo en un caso de uso nuevo simula reiniciar el proceso. */
class FakeRunningSessionStore : RunningSessionStore {
    private val store = MutableStateFlow<Map<String, RunningSession>>(emptyMap())
    val cleared = mutableListOf<String>()
    var onStart: suspend () -> Unit = {}

    override fun observe(uid: String): Flow<RunningSession?> = store.map { it[uid] }

    override suspend fun start(uid: String, session: RunningSession) {
        onStart()
        store.value = store.value + (uid to session)
    }

    override suspend fun clear(uid: String) {
        cleared += uid
        store.value = store.value - uid
    }
}
