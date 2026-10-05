package com.violinstudio.data.feature.account.repository

import app.cash.turbine.test
import com.google.firebase.auth.FirebaseAuthException
import com.violinstudio.data.commons.erasure.LocalUserDataEraser
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.auth.datasource.FakeAuthRemoteDataSource
import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.profile.datasource.FakeIdentityFunctionsDataSource
import com.violinstudio.domain.feature.account.failure.AccountFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountRepositoryImplTest {
    private val functions = FakeIdentityFunctionsDataSource()
    private val auth = FakeAuthRemoteDataSource()
    private val order = mutableListOf<String>()
    private val erasers = mutableListOf<LocalUserDataEraser>()
    private val repo by lazy { AccountRepositoryImpl(functions, auth, erasers.toSet()) }

    private fun eraser(name: String, failure: Exception? = null) = object : LocalUserDataEraser {
        override suspend fun erase(uid: String) {
            order += "$name:$uid"
            failure?.let { throw it }
        }
    }

    private fun failure(code: String, reason: String? = null) =
        FunctionsCallException(code, reason?.let { mapOf("reason" to it) })

    @Test
    fun `borrado correcto cierra la sesion local y authUser emite null`() = runTest {
        auth.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
        auth.authUser.test {
            assertEquals("u1", awaitItem()?.uid)
            functions.response = mapOf("deleted" to true)
            assertTrue(repo.deleteAccount().isSuccess)
            assertNull(awaitItem())
        }
        assertEquals(listOf("deleteAccount"), functions.calls.map { it.first })
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `cancelar al llamante durante el borrado no impide cerrar la sesion local`() = runTest {
        // El listener del perfil ve desaparecer el doc antes de que responda el callable, la sesion cambia y se cancela
        // el ViewModel que borra: el usuario ya no existe en el servidor y la sesion local debe cerrarse igualmente.
        auth.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
        val gate = CompletableDeferred<Unit>()
        functions.deleteGate = gate
        val job = launch { repo.deleteAccount() }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `un fallo de borrado no cierra la sesion`() = runTest {
        functions.failure = failure("FAILED_PRECONDITION", "REAUTH_REQUIRED")
        assertEquals(AccountFailure.RequiresRecentLogin, repo.deleteAccount().exceptionOrNull())
        functions.failure = failure("INTERNAL", "ERASURE_FAILED")
        assertEquals(AccountFailure.ErasureFailed, repo.deleteAccount().exceptionOrNull())
        functions.failure = IOException("sin red")
        assertEquals(AccountFailure.Network, repo.deleteAccount().exceptionOrNull())
        assertFalse(auth.calls.contains("signOut"))
    }

    @Test
    fun `unauthenticated con la cuenta desaparecida cuenta como ya borrada y cierra la sesion`() = runTest {
        functions.failure = failure("UNAUTHENTICATED")
        auth.reloadFailure = FirebaseAuthException("ERROR_USER_NOT_FOUND", "x")
        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals(listOf("reloadCurrentUser", "signOut"), auth.calls)
    }

    @Test
    fun `unauthenticated con usuario deshabilitado (borrado a medias) tambien cuenta como borrado`() = runTest {
        functions.failure = failure("UNAUTHENTICATED")
        auth.reloadFailure = FirebaseAuthException("ERROR_USER_DISABLED", "x")
        assertTrue(repo.deleteAccount().isSuccess)
        assertTrue(auth.calls.contains("signOut"))
    }

    @Test
    fun `unauthenticated con la cuenta existente es Unauthenticated y mantiene la sesion`() = runTest {
        functions.failure = failure("UNAUTHENTICATED")
        assertEquals(AccountFailure.Unauthenticated, repo.deleteAccount().exceptionOrNull())
        assertFalse(auth.calls.contains("signOut"))
    }

    @Test
    fun `unauthenticated sin poder verificar la cuenta falla sin cerrar la sesion`() = runTest {
        functions.failure = failure("UNAUTHENTICATED")
        auth.reloadFailure = IOException("sin red")
        assertTrue(repo.deleteAccount().exceptionOrNull() is AccountFailure.Unknown)
        auth.reloadFailure = IllegalStateException("Sin sesión")
        assertTrue(repo.deleteAccount().exceptionOrNull() is AccountFailure.Unknown)
        assertFalse(auth.calls.contains("signOut"))
    }

    @Test
    fun `un signOut que lanza no invalida un borrado ya hecho`() = runTest {
        auth.signOutFailure = IllegalStateException("boom")
        assertTrue(repo.deleteAccount().isSuccess)
    }

    @Test
    fun `un signOut que falla una vez se reintenta y el borrado sigue siendo correcto`() = runTest {
        auth.signOutFailures = 1
        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals(listOf("signOut", "signOut"), auth.calls)
    }

    @Test
    fun `si el cierre de sesion sigue fallando se limpia el estado local de autenticacion`() = runTest {
        auth.signOutFailure = IllegalStateException("boom")
        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals(listOf("signOut", "signOut", "signOut", "clearLocalSession"), auth.calls)
        assertEquals(null, auth.lastEmitted())
    }

    @Test
    fun `si tampoco se puede limpiar el estado local el borrado ya hecho sigue siendo correcto`() = runTest {
        auth.signOutFailure = IllegalStateException("boom")
        auth.clearFailure = IllegalStateException("boom2")
        assertTrue(repo.deleteAccount().isSuccess)
    }

    @Test
    fun `un signOut cancelado se propaga`() = runTest {
        auth.signOutFailure = CancellationException("cancelada")
        assertThrows<CancellationException> { repo.deleteAccount() }
    }

    @Test
    fun `cancelacion no cierra la sesion ni se convierte en fallo`() = runTest {
        functions.failure = CancellationException("cancelada")
        assertThrows<CancellationException> { repo.deleteAccount() }
        assertFalse(auth.calls.contains("signOut"))
    }

    @Test
    fun `los erasers se ejecutan antes del cierre de sesion local`() = runTest {
        auth.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
        erasers += eraser("a")
        erasers += eraser("b")
        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals(listOf("a:u1", "b:u1"), order)
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `un eraser que lanza no impide los demas ni el cierre de sesion`() = runTest {
        auth.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
        erasers += eraser("a", IllegalStateException("boom"))
        erasers += eraser("b")
        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals(listOf("a:u1", "b:u1"), order)
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `un fallo de borrado en el servidor no ejecuta los erasers`() = runTest {
        auth.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
        erasers += eraser("a")
        functions.failure = IOException("sin red")
        assertTrue(repo.deleteAccount().isFailure)
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun `cerrar sesion sin borrar la cuenta no ejecuta los erasers`() = runTest {
        erasers += eraser("a")
        repo // fuerza la construccion con el eraser registrado
        auth.signOut()
        assertEquals(emptyList<String>(), order)
    }
}
