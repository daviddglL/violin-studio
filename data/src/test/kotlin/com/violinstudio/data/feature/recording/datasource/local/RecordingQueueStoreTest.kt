package com.violinstudio.data.feature.recording.datasource.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.feature.recording.dto.QueuedRecordingDto
import com.violinstudio.domain.feature.recording.model.FailReason
import com.violinstudio.domain.feature.recording.model.QueueState
import com.violinstudio.domain.feature.recording.model.QueuedRecording
import com.violinstudio.domain.feature.recording.model.RecordedTake
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DataStore real bajo Robolectric SDK 34 (leccion 3); decodificacion por elemento y sin `IOException` (leccion 5). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RecordingQueueStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "${UUID.randomUUID()}.preferences_pb") }

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    private fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @After
    fun tearDown() = closeStores()

    private fun item(id: String, uid: String = "A") =
        QueuedRecording(RecordedTake(id, uid, "/nb/recordings/$uid/$id.m4a", 5_000, 1_000, 7))

    private val queueKey = stringPreferencesKey("u.A.rec.queue")

    @Test
    fun `la clave es u uid rec queue y la cola sobrevive a un proceso nuevo`() = runBlocking<Unit> {
        assertEquals("u.A.rec.queue", UserKeys.recQueue("A").name)
        val first = RecordingQueueStore(open())
        assertTrue(first.observe("A").first().isEmpty())
        first.enqueue(item("r1").copy(assignmentId = "as1", sharedWith = "S")).getOrThrow()
        closeStores()
        val back = RecordingQueueStore(open()).observe("A").first().single()
        assertEquals(item("r1").copy(assignmentId = "as1", sharedWith = "S"), back)
        assertEquals(7L, back.take.consentEpoch)
    }

    @Test
    fun `los estados avanzan recorded uploading uploaded done y failed guarda el motivo`() = runBlocking<Unit> {
        val store = RecordingQueueStore(open())
        store.enqueue(item("r1")).getOrThrow()
        val uploading = store.update(
            "A",
            "r1"
        ) { it.copy(state = QueueState.UPLOADING, attempts = 1, sessionUri = "s") }
        assertEquals(QueueState.UPLOADING, uploading.getOrThrow()?.state)
        store.update("A", "r1") { it.copy(state = QueueState.UPLOADED) }.getOrThrow()
        assertEquals(QueueState.UPLOADED, store.observe("A").first().single().state)
        assertEquals("s", store.observe("A").first().single().sessionUri)
        store.update("A", "r1") { it.copy(state = QueueState.FAILED, lastError = FailReason.QUOTA) }.getOrThrow()
        assertEquals(FailReason.QUOTA, store.observe("A").first().single().lastError)
        store.update("A", "r1") { it.copy(state = QueueState.DONE) }.getOrThrow()
        assertEquals(QueueState.DONE, store.observe("A").first().single().state)
        assertNull(store.update("A", "nope") { it }.getOrThrow())
    }

    @Test
    fun `reencolar el mismo id lo reemplaza y remove solo quita ese elemento`() = runBlocking<Unit> {
        val store = RecordingQueueStore(open())
        store.enqueue(item("r1")).getOrThrow()
        store.enqueue(item("r2")).getOrThrow()
        store.enqueue(item("r1").copy(attempts = 3)).getOrThrow()
        assertEquals(listOf("r1", "r2"), store.observe("A").first().map { it.id })
        assertEquals(2, store.count("A"))
        store.remove("A", "r1").getOrThrow()
        assertEquals(listOf("r2"), store.observe("A").first().map { it.id })
    }

    @Test
    fun `count no incluye los DONE, que ya cuenta el servidor`() = runBlocking<Unit> {
        val store = RecordingQueueStore(open())
        store.enqueue(item("r1")).getOrThrow()
        store.enqueue(item("r2").copy(state = QueueState.UPLOADED)).getOrThrow()
        store.enqueue(item("r3").copy(state = QueueState.FAILED, lastError = FailReason.QUOTA)).getOrThrow()
        store.enqueue(item("r4").copy(state = QueueState.DONE)).getOrThrow()
        assertEquals(3, store.count("A"))
    }

    @Test
    fun `cada uid ve y vacia solo su cola, y clear devuelve lo que habia`() = runBlocking<Unit> {
        val ds = open()
        val store = RecordingQueueStore(ds)
        store.enqueue(item("r1")).getOrThrow()
        store.enqueue(item("r2", uid = "B")).getOrThrow()
        assertTrue(store.observe("C").first().isEmpty())
        assertEquals(listOf("r1"), store.clear("A").getOrThrow().map { it.id })
        assertTrue(store.observe("A").first().isEmpty())
        assertEquals(listOf("r2"), store.observe("B").first().map { it.id })
        assertEquals(0, store.count("A"))
        assertTrue(ds.data.first().asMap().keys.all { UserKeys.belongsTo(it, "B") })
    }

    @Test
    fun `un elemento corrupto no tira la lista y un valor de otro tipo o no array da lista vacia`() =
        runBlocking<Unit> {
            val ds = open()
            val store = RecordingQueueStore(ds)
            store.enqueue(item("r1")).getOrThrow()
            val good = ds.data.first()[queueKey]!!.removeSurrounding("[", "]")
            ds.edit { it[queueKey] = "[$good,{\"id\":3},\"x\",{\"id\":\"r9\",\"state\":\"bogus\"},null]" }
            assertEquals(listOf("r1"), store.observe("A").first().map { it.id })
            ds.edit { it[queueKey] = "{no es un array" }
            assertTrue(store.observe("A").first().isEmpty())
            ds.edit { it[queueKey] = "{}" }
            assertTrue(store.observe("A").first().isEmpty())
            ds.edit { it.remove(queueKey) }
            ds.edit { it[longPreferencesKey("u.A.rec.queue")] = 5L }
            assertTrue(store.observe("A").first().isEmpty())
            assertEquals(0, store.count("A"))
        }

    @Test
    fun `una IOException de lectura no mata el flujo y una de escritura es un Result failure`() = runBlocking<Unit> {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disco") }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("disco")
        }
        val store = RecordingQueueStore(broken)
        assertTrue(store.observe("A").first().isEmpty())
        assertEquals(0, store.count("A"))
        assertTrue(store.enqueue(item("r1")).exceptionOrNull() is IOException)
        assertTrue(store.update("A", "r1") { it }.isFailure)
        assertTrue(store.remove("A", "r1").isFailure)
        assertTrue(store.clear("A").isFailure)
    }

    @Test
    fun `update no puede cambiar el id ni el uid de la toma y no escribe nada`() = runBlocking<Unit> {
        val store = RecordingQueueStore(open())
        store.enqueue(item("r1")).getOrThrow()
        val newId = store.update("A", "r1") { it.copy(take = it.take.copy(id = "otro")) }
        val newUid = store.update("A", "r1") { it.copy(take = it.take.copy(uid = "B")) }
        assertTrue(newId.isFailure)
        assertTrue(newUid.isFailure)
        assertEquals(listOf(item("r1")), store.observe("A").first())
        assertTrue(store.observe("B").first().isEmpty())
    }

    @Test
    fun `reencolar un id que ya avanzo es un no-op que conserva el progreso`() = runBlocking<Unit> {
        val store = RecordingQueueStore(open())
        store.enqueue(item("r1")).getOrThrow()
        store.update("A", "r1") { it.copy(state = QueueState.UPLOADING, attempts = 2, sessionUri = "s") }.getOrThrow()
        store.enqueue(item("r1")).getOrThrow()
        val kept = store.observe("A").first().single()
        assertEquals(QueueState.UPLOADING, kept.state)
        assertEquals(2, kept.attempts)
        assertEquals("s", kept.sessionUri)
    }

    @Test
    fun `un elemento de otro uid dentro de la clave de A se filtra`() = runBlocking<Unit> {
        val ds = open()
        val store = RecordingQueueStore(ds)
        store.enqueue(item("r1", uid = "B")).getOrThrow()
        val foreign = ds.data.first()[stringPreferencesKey("u.B.rec.queue")]!!
        ds.edit { it[queueKey] = foreign }
        assertTrue(store.observe("A").first().isEmpty())
        assertEquals(0, store.count("A"))
    }

    @Test
    fun `remove y clear sobre una cola corrupta no fallan y la limpian`() = runBlocking<Unit> {
        val ds = open()
        val store = RecordingQueueStore(ds)
        ds.edit { it[queueKey] = "{roto" }
        assertTrue(store.remove("A", "r1").isSuccess)
        ds.edit { it[queueKey] = "{roto" }
        assertTrue(store.clear("A").getOrThrow().isEmpty())
        assertNull(ds.data.first()[queueKey])
    }

    @Test
    fun `veinte update concurrentes no pierden ninguna actualizacion`() = runBlocking<Unit> {
        val store = RecordingQueueStore(open())
        store.enqueue(item("r1")).getOrThrow()
        (1..20).map { async(Dispatchers.Default) { store.update("A", "r1") { it.copy(attempts = it.attempts + 1) } } }
            .awaitAll().forEach { it.getOrThrow() }
        assertEquals(20, store.observe("A").first().single().attempts)
    }

    @Test
    fun `el toString del DTO no expone ruta, id ni sessionUri`() {
        val text = QueuedRecordingDto.from(item("r1").copy(sessionUri = "https://secreto")).toString()
        assertTrue(text, "m4a" !in text && "secreto" !in text && "r1" !in text)
    }
}
