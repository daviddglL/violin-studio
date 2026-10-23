package com.violinstudio.data.feature.consent.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.commons.erasure.DataStoreUserDataEraser
import com.violinstudio.data.feature.tuner.repository.DataStoreTunerConfigRepository
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bajo Robolectric con SDK 34, como `FirestoreLessonProgressRepositoryTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DataStoreIdentityConfigCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "${UUID.randomUUID()}.preferences_pb") }

    private val config = IdentityConfig(
        policyVersion = 2,
        policyUrl = "https://example.web.app/politica",
        digitalConsentAge = 14,
        guardianFlowEnabled = true,
        recordingsEnabled = true,
        videoEnabled = false,
        minorRecordingEnabled = true,
        recordingConsentVersion = 3,
        chatEnabled = true,
        notificationsEnabled = true
    )

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    private fun closeStores() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @After
    fun tearDown() = closeStores()

    @Test
    fun `sin dato es null y lo guardado sobrevive a un proceso nuevo`() = runBlocking<Unit> {
        val first = DataStoreIdentityConfigCache(open())
        assertNull(first.lastKnown("A"))
        first.remember("A", config)
        closeStores()
        assertEquals(config, DataStoreIdentityConfigCache(open()).lastKnown("A"))
    }

    @Test
    fun `cada uid ve solo la suya y guardar otra la reemplaza`() = runBlocking<Unit> {
        val cache = DataStoreIdentityConfigCache(open())
        cache.remember("A", config)
        cache.remember("A", config.copy(policyVersion = 3))
        assertEquals(3, cache.lastKnown("A")?.policyVersion)
        assertNull(cache.lastKnown("B"))
    }

    @Test
    fun `un JSON roto o un valor de otro tipo se leen como sin dato`() = runBlocking<Unit> {
        val ds = open()
        ds.edit { it[stringPreferencesKey("u.A.session.identity_config")] = "{roto" }
        ds.edit { it[intPreferencesKey("u.B.session.identity_config")] = 7 }
        val cache = DataStoreIdentityConfigCache(ds)
        assertNull(cache.lastKnown("A"))
        assertNull(cache.lastKnown("B"))
    }

    @Test
    fun `los interruptores ausentes quedan apagados`() = runBlocking<Unit> {
        val ds = open()
        val raw = """{"policyVersion":2,"policyUrl":"u","digitalConsentAge":14,"guardianFlowEnabled":true}"""
        ds.edit { it[stringPreferencesKey("u.A.session.identity_config")] = raw }
        assertEquals(
            IdentityConfig(policyVersion = 2, policyUrl = "u", digitalConsentAge = 14, guardianFlowEnabled = true),
            DataStoreIdentityConfigCache(ds).lastKnown("A")
        )
    }

    @Test
    fun `el eraser de datos locales la borra`() = runBlocking<Unit> {
        val ds = open()
        val cache = DataStoreIdentityConfigCache(ds)
        cache.remember("A", config)
        DataStoreUserDataEraser(DataStoreTunerConfigRepository(ds)).erase("A")
        assertNull(cache.lastKnown("A"))
    }
}
