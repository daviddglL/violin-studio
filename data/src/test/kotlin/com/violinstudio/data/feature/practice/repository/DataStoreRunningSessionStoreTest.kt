package com.violinstudio.data.feature.practice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.commons.erasure.DataStoreUserDataEraser
import com.violinstudio.data.feature.tuner.repository.DataStoreTunerConfigRepository
import com.violinstudio.data.feature.tuner.utils.UserKeys
import com.violinstudio.domain.feature.practice.model.RunningSession
import com.violinstudio.domain.feature.profile.model.Instrument
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreRunningSessionStoreTest {
    @TempDir
    lateinit var dir: File
    private val jobs = mutableListOf<Job>()

    /** Un fichero por test: en Windows el rename del `.tmp` falla si otro store sigue abierto. */
    private val file by lazy { File(dir, "${UUID.randomUUID()}.preferences_pb") }

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    private fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @AfterEach
    fun tearDown() = closeStores()

    private val session = RunningSession("id-1", Instant.ofEpochMilli(1_700_000_000_123), Instrument.VIOLA)

    @Test
    fun `sin datos no hay sesion y lo guardado sobrevive a un proceso nuevo`() = runBlocking {
        val first = DataStoreRunningSessionStore(open())
        assertNull(first.observe("A").first())
        first.start("A", session)
        closeStores()
        assertEquals(session, DataStoreRunningSessionStore(open()).observe("A").first())
    }

    @Test
    fun `otro uid no la ve y clear solo borra la propia`() = runBlocking {
        val store = DataStoreRunningSessionStore(open())
        store.start("A", session)
        store.start("B", session.copy(id = "id-2"))
        assertNull(store.observe("C").first())
        store.clear("A")
        assertNull(store.observe("A").first())
        assertEquals("id-2", store.observe("B").first()?.id)
    }

    @Test
    fun `valores corruptos o de otro tipo dan sin sesion sin fallar`() = runBlocking {
        val ds = open()
        val store = DataStoreRunningSessionStore(ds)
        ds.edit { it[stringPreferencesKey("u.A.practice.running.started_at")] = "hoy" }
        assertNull(store.observe("A").first())
        ds.edit {
            it[stringPreferencesKey("u.A.practice.running.id")] = "x"
            it[longPreferencesKey("u.A.practice.running.started_at")] = 5L
            it[stringPreferencesKey("u.A.practice.running.instrument")] = "theremin"
        }
        assertNull(store.observe("A").first())
    }

    @Test
    fun `el eraser de datos locales borra la sesion en curso`() = runBlocking {
        val ds = open()
        val store = DataStoreRunningSessionStore(ds)
        store.start("A", session)
        DataStoreUserDataEraser(DataStoreTunerConfigRepository(ds)).erase("A")
        assertNull(store.observe("A").first())
        assertTrue(ds.data.first().asMap().keys.none { UserKeys.belongsTo(it, "A") })
    }
}
