package com.violinstudio.data.feature.practice.repository

import android.app.Application
import android.os.Build
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

/**
 * Corre bajo Robolectric con SDK 34 (como producción, minSdk 26): DataStore renombra el `.tmp` con `Files.move`
 * (REPLACE_EXISTING) solo si `SDK_INT >= 26`; con SDK_INT = 0 (JVM puro) usa `File.renameTo`, que en Windows no
 * sobrescribe y hacía el resultado depender del JDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DataStoreRunningSessionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()

    /** Un fichero por test: en Windows el rename del `.tmp` falla si otro store sigue abierto. */
    private val file by lazy { File(tmp.root, "${UUID.randomUUID()}.preferences_pb") }

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    private fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @After
    fun tearDown() = closeStores()

    private val session = RunningSession("id-1", Instant.ofEpochMilli(1_700_000_000_123), Instrument.VIOLA)

    /** Guarda de configuración: si Robolectric dejara de dar SDK >= 26 volvería la dependencia del JDK en Windows. */
    @Test
    fun `el entorno corre con SDK 26 o superior como produccion`() {
        assertTrue(Build.VERSION.SDK_INT >= 26)
    }

    @Test
    fun `sin datos no hay sesion y lo guardado sobrevive a un proceso nuevo`() = runBlocking<Unit> {
        val first = DataStoreRunningSessionStore(open())
        assertNull(first.observe("A").first())
        first.start("A", session)
        closeStores()
        assertEquals(session, DataStoreRunningSessionStore(open()).observe("A").first())
    }

    @Test
    fun `otro uid no la ve y clear solo borra la propia`() = runBlocking<Unit> {
        val store = DataStoreRunningSessionStore(open())
        store.start("A", session)
        store.start("B", session.copy(id = "id-2"))
        assertNull(store.observe("C").first())
        store.clear("A")
        assertNull(store.observe("A").first())
        assertEquals("id-2", store.observe("B").first()?.id)
    }

    @Test
    fun `valores corruptos o de otro tipo dan sin sesion sin fallar`() = runBlocking<Unit> {
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
    fun `el eraser de datos locales borra la sesion en curso`() = runBlocking<Unit> {
        val ds = open()
        val store = DataStoreRunningSessionStore(ds)
        store.start("A", session)
        DataStoreUserDataEraser(DataStoreTunerConfigRepository(ds)).erase("A")
        assertNull(store.observe("A").first())
        assertTrue(ds.data.first().asMap().keys.none { UserKeys.belongsTo(it, "A") })
    }
}
