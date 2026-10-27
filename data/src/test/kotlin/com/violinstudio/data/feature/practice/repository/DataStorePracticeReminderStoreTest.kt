package com.violinstudio.data.feature.practice.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.commons.erasure.DataStoreUserDataEraser
import com.violinstudio.data.feature.tuner.repository.DataStoreTunerConfigRepository
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import java.io.File
import java.time.LocalTime
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

/** Robolectric con SDK 34, como [DataStoreRunningSessionStoreTest] (rename del `.tmp` de DataStore en Windows). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DataStorePracticeReminderStoreTest {
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

    private val reminder = PracticeReminder(enabled = true, time = LocalTime.of(19, 30))

    @Test
    fun `sin datos esta apagado a las 18 y lo guardado sobrevive a un proceso nuevo`() = runBlocking<Unit> {
        val first = DataStorePracticeReminderStore(open())
        assertEquals(PracticeReminder(), first.observe("A").first())
        first.set("A", reminder)
        closeStores()
        assertEquals(reminder, DataStorePracticeReminderStore(open()).observe("A").first())
    }

    @Test
    fun `cada uid tiene el suyo`() = runBlocking<Unit> {
        val store = DataStorePracticeReminderStore(open())
        store.set("A", reminder)
        assertEquals(PracticeReminder(), store.observe("B").first())
    }

    @Test
    fun `valores de otro tipo o fuera de rango dan el de por defecto`() = runBlocking<Unit> {
        val ds = open()
        val store = DataStorePracticeReminderStore(ds)
        ds.edit {
            it[stringPreferencesKey("u.A.practice.reminder.enabled")] = "si"
            it[intPreferencesKey("u.A.practice.reminder.minute")] = 1_440
        }
        assertEquals(PracticeReminder(), store.observe("A").first())
    }

    @Test
    fun `el eraser de datos locales lo borra`() = runBlocking<Unit> {
        val ds = open()
        val store = DataStorePracticeReminderStore(ds)
        store.set("A", reminder)
        DataStoreUserDataEraser(DataStoreTunerConfigRepository(ds)).erase("A")
        assertEquals(PracticeReminder(), store.observe("A").first())
        assertTrue(ds.data.first().asMap().keys.none { UserKeys.belongsTo(it, "A") })
    }
}
