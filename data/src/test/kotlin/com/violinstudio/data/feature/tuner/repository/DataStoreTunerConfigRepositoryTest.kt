package com.violinstudio.data.feature.tuner.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreTunerConfigRepositoryTest {
    @TempDir
    lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()
    private val file get() = File(dir, "user_local.preferences_pb")

    private fun open(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        scopes += scope
        return createUserLocalDataStore(file, scope)
    }

    private fun repo() = DataStoreTunerConfigRepository(open())

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    private val preset = TuningConfiguration("p1", "Barroco", ReferencePitch(415.0), MaxCents(75))
    private val configA = TunerConfig(ReferencePitch(442.0), MaxCents(100), listOf(preset), "p1")

    @Test
    fun `sin datos observa defectos y lo escrito sobrevive a un proceso nuevo`() = runBlocking {
        val first = repo()
        assertEquals(TunerConfig(), first.observe("A").first())
        first.update("A") { configA }
        scopes.forEach { it.cancel() }
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
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.update("A") { error("boom") } }
        }
        assertEquals(configA, repo.observe("A").first())
    }

    @Test
    fun `un fichero corrupto se reemplaza por defectos`() = runBlocking {
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals(TunerConfig(), repo().observe("A").first())
    }
}
