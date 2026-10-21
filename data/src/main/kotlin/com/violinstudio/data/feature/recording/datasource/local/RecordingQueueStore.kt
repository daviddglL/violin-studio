package com.violinstudio.data.feature.recording.datasource.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.violinstudio.data.feature.recording.dto.QueuedRecordingDto
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.repository.PendingUploads
import com.violinstudio.domain.feature.recording.repository.RecordingQueue
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * Cola de subida en el DataStore por usuario (A14): `u.{uid}.rec.queue` = JSON con una lista de [QueuedRecordingDto].
 * Leer nunca falla: la `IOException` es "cola vacia" y se decodifica por elemento (uno corrupto se descarta; un valor
 * de otro tipo o JSON roto es lista vacia). Escribir devuelve `Result.failure(IOException)`. Cada mutacion es una sola
 * transaccion `edit` (leer-modificar-escribir atomico). Los logs quedan fuera: ninguna ruta ni id sale de aqui.
 */
@Singleton
class RecordingQueueStore @Inject constructor(private val store: DataStore<Preferences>) :
    RecordingQueue,
    PendingUploads {
    override fun observe(uid: String): Flow<List<QueuedRecording>> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it, uid) }
        .distinctUntilChanged()

    override suspend fun count(uid: String): Int = observe(uid).first().size

    override suspend fun enqueue(item: QueuedRecording): Result<Unit> = mutate(item.take.uid) { list ->
        val at = list.indexOfFirst { it.id == item.id }
        if (at >= 0) list[at] = item else list += item
    }

    override suspend fun update(
        uid: String,
        id: String,
        transform: (QueuedRecording) -> QueuedRecording
    ): Result<QueuedRecording?> = mutate(uid) { list ->
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) null else transform(list[at]).also { list[at] = it }
    }

    override suspend fun remove(uid: String, id: String): Result<Unit> = mutate(uid) { list ->
        list.removeAll { it.id == id }
        Unit
    }

    override suspend fun clear(uid: String): Result<List<QueuedRecording>> = mutate(uid) { list ->
        list.toList().also { list.clear() }
    }

    private suspend fun <T> mutate(uid: String, block: (MutableList<QueuedRecording>) -> T): Result<T> = try {
        var result: T? = null
        store.edit { prefs ->
            val list = read(prefs, uid).toMutableList()
            result = block(list)
            val key = UserKeys.recQueue(uid)
            if (list.isEmpty()) prefs.remove(key) else prefs[key] = json.encodeToString(list.map(QueuedRecordingDto::from))
        }
        @Suppress("UNCHECKED_CAST")
        Result.success(result as T)
    } catch (e: IOException) {
        Result.failure(e)
    }

    // `prefs[key]` lanza ClassCastException con un valor de otro tipo: se busca por nombre y se castea con `as?`.
    private fun read(prefs: Preferences, uid: String): List<QueuedRecording> {
        val name = UserKeys.recQueue(uid).name
        val raw = prefs.asMap().entries.firstOrNull { it.key.name == name }?.value as? String ?: return emptyList()
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull().orEmpty()
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(QueuedRecordingDto.serializer(), element) }.getOrNull()?.toDomain()
        }.filter { it.take.uid == uid }.distinctBy { it.id }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
