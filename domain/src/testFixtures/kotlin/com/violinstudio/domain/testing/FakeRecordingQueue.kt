package com.violinstudio.domain.testing

import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Cola en memoria con la semantica de `RecordingQueueStore`. [failWrites] hace fallar toda escritura con IOException. */
class FakeRecordingQueue(initial: List<QueuedRecording> = emptyList()) :
    RecordingQueue,
    PendingUploads {
    val items = MutableStateFlow(initial)
    var failWrites = false

    override suspend fun enqueue(item: QueuedRecording): Result<Unit> = write {
        if (it.none { q -> q.id == item.id }) it + item else it
    }

    override fun observe(uid: String): Flow<List<QueuedRecording>> =
        items.map { list -> list.filter { it.take.uid == uid } }.distinctUntilChanged()

    override suspend fun update(
        uid: String,
        id: String,
        transform: (QueuedRecording) -> QueuedRecording
    ): Result<QueuedRecording?> {
        if (failWrites) return Result.failure(IOException("disco"))
        val current = items.value.firstOrNull { it.take.uid == uid && it.id == id } ?: return Result.success(null)
        val updated = transform(current)
        items.value = items.value.map { if (it === current) updated else it }
        return Result.success(updated)
    }

    override suspend fun remove(uid: String, id: String): Result<Unit> =
        write { list -> list.filterNot { it.take.uid == uid && it.id == id } }

    override suspend fun clear(uid: String): Result<List<QueuedRecording>> {
        if (failWrites) return Result.failure(IOException("disco"))
        val removed = items.value.filter { it.take.uid == uid }
        items.value = items.value - removed.toSet()
        return Result.success(removed)
    }

    override suspend fun count(uid: String): Int =
        items.value.count { it.take.uid == uid && it.state != QueueState.DONE }

    fun get(id: String): QueuedRecording? = items.value.firstOrNull { it.id == id }

    private fun write(block: (List<QueuedRecording>) -> List<QueuedRecording>): Result<Unit> {
        if (failWrites) return Result.failure(IOException("disco"))
        items.value = block(items.value)
        return Result.success(Unit)
    }
}
