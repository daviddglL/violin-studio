package com.violinstudio.data.feature.tuner.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.feature.tuner.utils.UserKeys
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreTunerConfigRepositoryTest {
    @TempDir
    lateinit var dir: File
    private val jobs = mutableListOf<Job>()

    /** Cada test usa su propio fichero: en Windows el rename del `.tmp` falla si otro store sigue abierto. */
    private val file by lazy { File(dir, "${UUID.randomUUID()}.preferences_pb") }

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    private fun repo() = DataStoreTunerConfigRepository(open())

    private fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @AfterEach
    fun tearDown() = closeStores()

    private val preset = TuningConfiguration("p1", "Barroco", ReferencePitch(415.0), MaxCents(75))
    private val configA = TunerConfig(ReferencePitch(442.0), MaxCents(100), listOf(preset), "p1")

    @Test
    fun `sin datos observa defectos y lo escrito sobrevive a un proceso nuevo`() = runBlocking {
        val first = repo()
        assertEquals(TunerConfig(), first.observe("A").first())
        first.update("A") { configA }
        closeStores()
        assertEquals(configA, repo().observe("A").first())
    }

    @Test
    fun `un uid no ve los datos de otro y clear de A deja intacto B`() = runBlocking {
        val repo = repo()
        repo.update("A") { configA }
        repo.update("B") { it.copy(maxCents = MaxCents(25)) }
        assertEquals(TunerConfig(), repo.observe("C").first())
        repo.clear("A")
        assertEquals(TunerConfig(), repo.observe("A").first())
        assertEquals(MaxCents(25), repo.observe("B").first().maxCents)
    }

    @Test
    fun `clear sin datos es idempotente`() = runBlocking {
        val repo = repo()
        repo.clear("A")
        repo.clear("A")
        assertEquals(TunerConfig(), repo.observe("A").first())
    }

    @Test
    fun `una transformacion que lanza no escribe nada`() = runBlocking {
        val repo = repo()
        repo.update("A") { configA }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.update("A") { error("boom") } } }
        assertEquals(configA, repo.observe("A").first())
    }

    @Test
    fun `un fichero corrupto se reemplaza por defectos`() = runBlocking {
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals(TunerConfig(), repo().observe("A").first())
    }

    @Test
    fun `actualizaciones concurrentes del mismo uid no pierden ninguna`() = runBlocking {
        val repo = repo()
        List(10) { i ->
            async(Dispatchers.IO) {
                repo.update("A") { c ->
                    c.copy(presets = c.presets + TuningConfiguration("p$i", "p$i", ReferencePitch(440.0), MaxCents(50)))
                }
            }
        }.awaitAll()
        assertEquals(10, repo.observe("A").first().presets.size)
    }

    @Test
    fun `un esquema posterior no se degrada, se lee como defectos y escribir falla`() = runBlocking {
        val store = open()
        store.edit { it[UserKeys.tunerVersion("A")] = UserKeys.SCHEMA_VERSION + 1 }
        val repo = DataStoreTunerConfigRepository(store)
        assertEquals(TunerConfig(), repo.observe("A").first())
        assertThrows(TunerFailure.StorageUnavailable::class.java) { runBlocking { repo.update("A") { configA } } }
        assertEquals(UserKeys.SCHEMA_VERSION + 1, store.data.first()[UserKeys.tunerVersion("A")])
    }

    @Test
    fun `los fallos de disco se leen como defectos y al escribir o borrar son StorageUnavailable`() = runBlocking {
        val repo = DataStoreTunerConfigRepository(BrokenStore)
        assertEquals(TunerConfig(), repo.observe("A").first())
        assertThrows(TunerFailure.StorageUnavailable::class.java) { runBlocking { repo.update("A") { configA } } }
        assertThrows(TunerFailure.StorageUnavailable::class.java) { runBlocking { repo.clear("A") } }
    }

    private object BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disco lleno") }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("rename fallido")
    }
}
