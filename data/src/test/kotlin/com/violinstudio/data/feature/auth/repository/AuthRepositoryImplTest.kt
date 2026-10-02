package com.violinstudio.data.feature.auth.repository

import app.cash.turbine.test
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.violinstudio.data.feature.auth.datasource.FakeAuthRemoteDataSource
import com.violinstudio.data.feature.auth.dto.AuthUserDto
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.profile.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AuthRepositoryImplTest {
    private val remote = FakeAuthRemoteDataSource()
    private val repo = AuthRepositoryImpl(remote)

    @Test
    fun `authUser mapea a dominio y emite null sin sesion`() = runTest {
        repo.authUser.test {
            remote.emit(null)
            assertNull(awaitItem())
            remote.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
            val user = awaitItem()!!
            assertEquals("u1", user.uid)
            assertEquals(setOf(AuthProvider.PASSWORD), user.providers)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `authUser no repite emisiones iguales`() = runTest {
        repo.authUser.test {
            val dto = AuthUserDto("u1", "a@b.co", true, listOf("password"))
            remote.emit(dto)
            awaitItem()
            remote.emit(dto.copy())
            expectNoEvents()
            remote.emit(dto.copy(emailVerified = false))
            assertFalse(awaitItem()!!.emailVerified)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reloadAndRefreshToken devuelve el usuario y authUser reemite`() = runTest {
        remote.user = AuthUserDto("u1", "a@b.co", true, listOf("password"))
        repo.authUser.test {
            remote.emit(AuthUserDto("u1", "a@b.co", false, listOf("password")))
            assertFalse(awaitItem()!!.emailVerified)
            val result = repo.reloadAndRefreshToken()
            assertTrue(result.getOrThrow().emailVerified)
            assertTrue(awaitItem()!!.emailVerified)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signIn correcto devuelve el usuario de dominio`() = runTest {
        val user = repo.signInWithEmail("a@b.co", "pw").getOrThrow()
        assertEquals("u1", user.uid)
        assertEquals(listOf("signIn:a@b.co"), remote.calls)
    }

    @Test
    fun `signIn con contrasena erronea y con usuario inexistente fallan igual`() = runTest {
        remote.failure = FirebaseAuthException("ERROR_WRONG_PASSWORD", "x")
        val wrong = repo.signInWithEmail("a@b.co", "pw").exceptionOrNull()
        remote.failure = FirebaseAuthException("ERROR_USER_NOT_FOUND", "x")
        val missing = repo.signInWithEmail("z@b.co", "pw").exceptionOrNull()
        assertSame(AuthFailure.InvalidCredentials, wrong)
        assertSame(wrong, missing)
    }

    @Test
    fun `signUp mapea email en uso y contrasena debil`() = runTest {
        remote.failure = FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "x")
        assertSame(AuthFailure.EmailAlreadyInUse, repo.signUpWithEmail("a@b.co", "pw").exceptionOrNull())
        remote.failure = FirebaseAuthException("ERROR_WEAK_PASSWORD", "x")
        assertSame(AuthFailure.WeakPassword, repo.signUpWithEmail("a@b.co", "pw").exceptionOrNull())
    }

    @Test
    fun `sendEmailVerification y reload mapean red`() = runTest {
        remote.failure = FirebaseNetworkException("x")
        assertSame(AuthFailure.Network, repo.sendEmailVerification().exceptionOrNull())
        assertSame(AuthFailure.Network, repo.reloadAndRefreshToken().exceptionOrNull())
    }

    @Test
    fun `signInWithGoogle envia el valor del token y conserva Google como proveedor`() = runTest {
        remote.user = AuthUserDto("u2", "g@b.co", false, listOf("google.com"))
        val user = repo.signInWithGoogle(GoogleIdToken("tok", "raw-nonce")).getOrThrow()
        assertEquals(listOf("google:tok:raw-nonce"), remote.calls)
        assertTrue(user.emailVerified)
        remote.failure = FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "x")
        assertSame(AuthFailure.ProviderUnavailable, repo.signInWithGoogle(GoogleIdToken("tok")).exceptionOrNull())
        remote.failure = FirebaseAuthException("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL", "x")
        assertSame(
            AuthFailure.AccountExistsWithOtherProvider,
            repo.signInWithGoogle(GoogleIdToken("tok")).exceptionOrNull()
        )
    }

    @Test
    fun `sendPasswordReset devuelve UserNotFound solo para que el caso de uso lo oculte`() = runTest {
        assertTrue(repo.sendPasswordReset("a@b.co").isSuccess)
        remote.failure = FirebaseAuthException("ERROR_USER_NOT_FOUND", "x")
        assertSame(AuthFailure.UserNotFound, repo.sendPasswordReset("z@b.co").exceptionOrNull())
    }

    @Test
    fun `claims respeta forceRefresh y mapea el rol`() = runTest {
        assertEquals(Role.INDEPENDENT, repo.claims(false).getOrThrow().role)
        assertTrue(repo.claims(true).getOrThrow().consentOk)
        assertEquals(listOf("claims:false", "claims:true"), remote.calls)
    }

    @Test
    fun `reautenticacion mapea contrasena erronea y login reciente`() = runTest {
        assertTrue(repo.reauthenticateWithPassword("pw").isSuccess)
        assertTrue(repo.reauthenticateWithGoogle(GoogleIdToken("t", "raw-2")).isSuccess)
        assertEquals(listOf("reauthGoogle:t:raw-2"), remote.calls.takeLast(1))
        remote.failure = FirebaseAuthException("ERROR_WRONG_PASSWORD", "x")
        assertSame(AuthFailure.InvalidCredentials, repo.reauthenticateWithPassword("bad").exceptionOrNull())
        remote.failure = FirebaseAuthException("ERROR_REQUIRES_RECENT_LOGIN", "x")
        assertSame(AuthFailure.RequiresRecentLogin, repo.reauthenticateWithGoogle(GoogleIdToken("t")).exceptionOrNull())
    }

    @Test
    fun `signOut cierra la sesion y authUser emite null`() = runTest {
        repo.authUser.test {
            remote.emit(AuthUserDto("u1", "a@b.co", true, listOf("password")))
            awaitItem()
            repo.signOut()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `un error desconocido conserva la causa y la cancelacion se relanza`() = runTest {
        val cause = IllegalStateException("boom")
        remote.failure = cause
        val failure = repo.signInWithEmail("a@b.co", "pw").exceptionOrNull()
        assertTrue(failure is AuthFailure.Unknown)
        assertSame(cause, failure!!.cause)

        remote.failure = CancellationException("cancelado")
        assertThrows<CancellationException> { runBlocking { repo.claims(false) } }
    }
}
