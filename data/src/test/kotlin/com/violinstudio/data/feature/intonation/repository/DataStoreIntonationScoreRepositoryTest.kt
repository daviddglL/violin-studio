package com.violinstudio.data.feature.intonation.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.commons.erasure.DataStoreUserDataEraser
import com.violinstudio.data.feature.tuner.repository.DataStoreTunerConfigRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric con SDK 34 por lo mismo que `DataStoreRunningSessionStoreTest` (rename del `.tmp` de DataStore). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DataStoreIntonationScoreRepositoryTest {
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

    @Test
    fun `solo guarda si mejora y sobrevive a un proceso nuevo`() = runBlocking<Unit> {
        val repo = DataStoreIntonationScoreRepository(open())
        assertEquals(emptyMap<String, Int>(), repo.observeBest("A").first())
        repo.saveIfBest("A", "d_major_one_octave", 70)
        repo.saveIfBest("A", "d_major_one_octave", 40)
        repo.saveIfBest("A", "a_string_pattern", 0)
        closeStores()
        assertEquals(
            mapOf("d_major_one_octave" to 70, "a_string_pattern" to 0),
            DataStoreIntonationScoreRepository(open()).observeBest("A").first()
        )
    }

    @Test
    fun `cada uid ve las suyas y un valor de otro tipo se ignora`() = runBlocking<Unit> {
        val ds = open()
        val repo = DataStoreIntonationScoreRepository(ds)
        repo.saveIfBest("A", "x", 10)
        repo.saveIfBest("B", "x", 20)
        ds.edit { it[stringPreferencesKey("u.A.intonation.best.y")] = "mucho" }
        assertEquals(mapOf("x" to 10), repo.observeBest("A").first())
        assertEquals(mapOf("x" to 20), repo.observeBest("B").first())
    }

    @Test
    fun `el eraser de datos locales borra las puntuaciones`() = runBlocking<Unit> {
        val ds = open()
        val repo = DataStoreIntonationScoreRepository(ds)
        repo.saveIfBest("A", "x", 10)
        DataStoreUserDataEraser(DataStoreTunerConfigRepository(ds)).erase("A")
        assertEquals(emptyMap<String, Int>(), repo.observeBest("A").first())
        assertTrue(ds.data.first().asMap().keys.none { UserKeys.belongsTo(it, "A") })
    }
}
