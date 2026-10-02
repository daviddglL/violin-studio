package com.violinstudio.data.feature.account.repository

import app.cash.turbine.test
import com.violinstudio.data.commons.firebase.FunctionsCallException
import com.violinstudio.data.feature.auth.datasource.FakeAuthRemoteDataSource
import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.data.feature.profile.datasource.FakeIdentityFunctionsDataSource
import com.violinstudio.domain.feature.account.failure.AccountFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    private val repo = AccountRepositoryImpl(functions, auth)

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
    fun `unauthenticated en un reintento cuenta como cuenta ya borrada y cierra la sesion`() = runTest {
        functions.failure = failure("UNAUTHENTICATED")
        assertTrue(repo.deleteAccount().isSuccess)
        assertEquals(listOf("signOut"), auth.calls)
    }

    @Test
    fun `cancelacion no cierra la sesion ni se convierte en fallo`() = runTest {
        functions.failure = CancellationException("cancelada")
        assertThrows<CancellationException> { repo.deleteAccount() }
        assertFalse(auth.calls.contains("signOut"))
    }
}
