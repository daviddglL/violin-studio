package com.violinstudio.data.feature.curriculum.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.commons.erasure.DataStoreUserDataEraser
import com.violinstudio.data.feature.tuner.repository.DataStoreTunerConfigRepository
import com.violinstudio.data.commons.datastore.UserKeys
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

/** Bajo Robolectric con SDK 34, como `DataStoreRunningSessionStoreTest` (rename del `.tmp` como en produccion). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DataStoreLessonProgressRepositoryTest {
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
    fun `sin datos no hay nada hecho y lo guardado sobrevive a un proceso nuevo`() = runBlocking<Unit> {
        val first = DataStoreLessonProgressRepository(open())
        assertEquals(emptySet<String>(), first.observeCompleted("A").first())
        first.setCompleted("A", "b1_posture", true)
        first.setCompleted("A", "b2_bow_hold", true)
        closeStores()
        assertEquals(
            setOf("b1_posture", "b2_bow_hold"),
            DataStoreLessonProgressRepository(open()).observeCompleted("A").first()
        )
    }

    @Test
    fun `desmarcar quita la leccion y cada uid ve solo lo suyo`() = runBlocking<Unit> {
        val repo = DataStoreLessonProgressRepository(open())
        repo.setCompleted("A", "b1_posture", true)
        repo.setCompleted("B", "b4_tuning", true)
        repo.setCompleted("A", "b1_posture", false)
        assertEquals(emptySet<String>(), repo.observeCompleted("A").first())
        assertEquals(setOf("b4_tuning"), repo.observeCompleted("B").first())
    }

    @Test
    fun `un valor de otro tipo se lee como nada hecho sin fallar`() = runBlocking<Unit> {
        val ds = open()
        ds.edit { it[stringPreferencesKey("u.A.curriculum.completed")] = "b1_posture" }
        assertEquals(emptySet<String>(), DataStoreLessonProgressRepository(ds).observeCompleted("A").first())
    }

    @Test
    fun `el eraser de datos locales borra el progreso`() = runBlocking<Unit> {
        val ds = open()
        val repo = DataStoreLessonProgressRepository(ds)
        repo.setCompleted("A", "b1_posture", true)
        DataStoreUserDataEraser(DataStoreTunerConfigRepository(ds)).erase("A")
        assertEquals(emptySet<String>(), repo.observeCompleted("A").first())
        assertTrue(ds.data.first().asMap().keys.none { UserKeys.belongsTo(it, "A") })
    }
}
