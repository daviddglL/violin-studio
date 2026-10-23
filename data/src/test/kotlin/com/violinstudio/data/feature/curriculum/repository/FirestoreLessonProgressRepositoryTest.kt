package com.violinstudio.data.feature.curriculum.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.feature.curriculum.datasource.CurriculumProgressRemoteDataSource
import com.violinstudio.domain.feature.curriculum.failure.CurriculumFailure
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Doc remoto en memoria: `add`/`remove` cambian la lista al instante, como la cache del SDK. */
private class FakeRemote : CurriculumProgressRemoteDataSource {
    val docs = mutableMapOf<String, MutableStateFlow<List<String>>>()
    var observeFailure: Throwable? = null
    var writeFailure: Exception? = null

    /** Si no es nulo, `addAndAwaitServer` espera a que se complete (confirmacion del servidor). */
    var serverAck: CompletableDeferred<Unit>? = null

    private fun doc(uid: String) = docs.getOrPut(uid) { MutableStateFlow(emptyList()) }

    override fun observe(uid: String): Flow<List<String>> {
        val failure = observeFailure
        return if (failure != null) flow { throw failure } else doc(uid).map { it }
    }

    override fun add(uid: String, ids: Collection<String>) {
        writeFailure?.let { throw it }
        doc(uid).value = (doc(uid).value + ids).distinct()
    }

    override suspend fun addAndAwaitServer(uid: String, ids: Collection<String>) {
        add(uid, ids)
        serverAck?.await()
    }

    override fun remove(uid: String, id: String) {
        writeFailure?.let { throw it }
        doc(uid).value = doc(uid).value - id
    }
}

/** Bajo Robolectric con SDK 34 por el DataStore real del progreso antiguo (como `DataStoreRunningSessionStoreTest`). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FirestoreLessonProgressRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "${UUID.randomUUID()}.preferences_pb") }
    private val remote = FakeRemote()

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    private suspend fun DataStore<Preferences>.legacyKeys(uid: String) =
        data.first().asMap().keys.filter { UserKeys.belongsTo(it, uid) }

    @Test
    fun `marcar y desmarcar escriben en la nube y cada uid ve solo lo suyo`() = runBlocking<Unit> {
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(open()))
        assertEquals(emptySet<String>(), repo.observeCompleted("A").first())
        repo.setCompleted("A", "b1_posture", true)
        repo.setCompleted("A", "b2_bow_hold", true)
        repo.setCompleted("B", "b4_tuning", true)
        repo.setCompleted("A", "b1_posture", false)
        assertEquals(setOf("b2_bow_hold"), repo.observeCompleted("A").first())
        assertEquals(setOf("b4_tuning"), repo.observeCompleted("B").first())
    }

    @Test
    fun `lo guardado en el dispositivo se une con la nube y se borra al confirmarse`() = runBlocking<Unit> {
        val ds = open()
        ds.edit { it[UserKeys.completedLessons("A")] = setOf("b1_posture", "b2_bow_hold") }
        remote.docs["A"] = MutableStateFlow(listOf("b2_bow_hold", "b3_open_strings"))
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(ds))

        val all = setOf("b1_posture", "b2_bow_hold", "b3_open_strings")
        withTimeout(5_000) { repo.observeCompleted("A").first { it == all } }
        withTimeout(5_000) { while (ds.legacyKeys("A").isNotEmpty()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `sin confirmacion del servidor lo local se conserva para el siguiente intento`() = runBlocking<Unit> {
        val ds = open()
        ds.edit { it[UserKeys.completedLessons("A")] = setOf("b1_posture") }
        remote.serverAck = CompletableDeferred()
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(ds))

        withTimeout(5_000) { repo.observeCompleted("A").first { it == setOf("b1_posture") } }
        assertEquals(1, ds.legacyKeys("A").size)
    }

    @Test
    fun `si la subida falla lo local se queda y la lectura sigue`() = runBlocking<Unit> {
        val ds = open()
        ds.edit { it[UserKeys.completedLessons("A")] = setOf("b1_posture") }
        remote.writeFailure = IllegalStateException("rechazo local")
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(ds))

        assertEquals(emptySet<String>(), repo.observeCompleted("A").first())
        assertEquals(1, ds.legacyKeys("A").size)
    }

    @Test
    fun `un valor local de otro tipo no se sube ni falla`() = runBlocking<Unit> {
        val ds = open()
        ds.edit { it[stringPreferencesKey("u.A.curriculum.completed")] = "b1_posture" }
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(ds))
        assertEquals(emptySet<String>(), repo.observeCompleted("A").first())
        assertEquals(emptyList<String>(), remote.docs.getValue("A").value)
    }

    @Test
    fun `un fallo del listener es nada hecho y el flujo termina`() = runBlocking<Unit> {
        remote.observeFailure = IllegalStateException("permission-denied")
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(open()))
        assertEquals(listOf(emptySet<String>()), withTimeout(5_000) { repo.observeCompleted("A").toList() })
    }

    @Test
    fun `un rechazo local al escribir es Storage`() = runBlocking<Unit> {
        remote.writeFailure = IllegalStateException("x")
        val repo = FirestoreLessonProgressRepository(remote, LegacyLessonProgressStore(open()))
        try {
            repo.setCompleted("A", "b1_posture", true)
            fail("debia lanzar")
        } catch (e: CurriculumFailure) {
            assertSame(CurriculumFailure.Storage, e)
        }
        assertTrue(remote.docs["A"]?.value.isNullOrEmpty())
    }
}
