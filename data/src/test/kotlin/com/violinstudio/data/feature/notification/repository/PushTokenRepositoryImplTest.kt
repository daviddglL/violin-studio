package com.violinstudio.data.feature.notification.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.violinstudio.data.commons.datastore.UserKeys
import com.violinstudio.data.commons.datastore.createUserLocalDataStore
import com.violinstudio.data.feature.notification.datasource.DeviceFunctionsDataSource
import com.violinstudio.data.feature.notification.datasource.PushTokenSource
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PushTokenRepositoryImplTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(tmp.root, "${UUID.randomUUID()}.preferences_pb") }
    private val calls = mutableListOf<String>()
    private var failServer = false

    private val source = object : PushTokenSource {
        var current = "tok-1"

        override suspend fun token(): String {
            calls += "token"
            return current
        }

        override suspend fun delete() {
            calls += "delete"
        }
    }

    private val functions = object : DeviceFunctionsDataSource {
        override suspend fun register(token: String) {
            calls += "register:$token"
            if (failServer) error("offline")
        }

        override suspend fun unregister(token: String) {
            calls += "unregister:$token"
            if (failServer) error("offline")
        }
    }

    private fun open(): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return createUserLocalDataStore(file, CoroutineScope(Dispatchers.IO + job))
    }

    private suspend fun DataStore<Preferences>.token(uid: String): String? =
        data.first().asMap().entries.firstOrNull { it.key.name == UserKeys.pushToken(uid).name }?.value as? String

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun `registrar pide el token, lo manda al servidor y lo recuerda bajo el uid`() = runBlocking<Unit> {
        val store = open()
        assertTrue(PushTokenRepositoryImpl(source, functions, store).register("A").isSuccess)
        assertEquals(listOf("token", "register:tok-1"), calls)
        assertEquals("tok-1", store.token("A"))
        assertNull(store.token("B"))
    }

    @Test
    fun `si el servidor falla no se recuerda nada`() = runBlocking<Unit> {
        val store = open()
        failServer = true
        assertTrue(PushTokenRepositoryImpl(source, functions, store).register("A").isFailure)
        assertNull(store.token("A"))
    }

    @Test
    fun `dar de baja sin token registrado no toca FCM ni el servidor`() = runBlocking<Unit> {
        assertTrue(PushTokenRepositoryImpl(source, functions, open()).unregister("A").isSuccess)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `dar de baja avisa al servidor, borra el token de FCM y olvida el local`() = runBlocking<Unit> {
        val store = open()
        val repo = PushTokenRepositoryImpl(source, functions, store)
        repo.register("A")
        calls.clear()
        assertTrue(repo.unregister("A").isSuccess)
        assertEquals(listOf("unregister:tok-1", "delete"), calls)
        assertNull(store.token("A"))
    }

    @Test
    fun `sin conexion la baja borra igual el token de FCM y el local`() = runBlocking<Unit> {
        val store = open()
        val repo = PushTokenRepositoryImpl(source, functions, store)
        repo.register("A")
        failServer = true
        calls.clear()
        assertTrue(repo.unregister("A").isFailure)
        assertEquals(listOf("unregister:tok-1", "delete"), calls)
        assertNull(store.token("A"))
    }

    @Test
    fun `un token nuevo solo se registra si el usuario ya tenia uno`() = runBlocking<Unit> {
        val store = open()
        val repo = PushTokenRepositoryImpl(source, functions, store)
        repo.onNewToken("A", "tok-2")
        assertEquals(emptyList<String>(), calls)
        repo.register("A")
        calls.clear()
        repo.onNewToken("A", "tok-2")
        assertEquals(listOf("register:tok-2"), calls)
        assertEquals("tok-2", store.token("A"))
    }
}
