package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.usecase.SignInWithEmailUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val signIn = mockk<SignInWithEmailUseCase>()
    private val user = AuthUser("uid", "ana@example.test", true, setOf(AuthProvider.PASSWORD))

    private fun viewModel() = LoginViewModel(signIn, mockk())

    private fun answers(result: Result<AuthUser>) {
        coEvery { signIn(any(), any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun MviScenario<LoginState, LoginIntent, LoginEffect>.fill() {
        intent(LoginIntent.EmailChanged("ana@example.test"))
        assertState { it.email == "ana@example.test" }
        intent(LoginIntent.PasswordChanged("secret"))
        assertState { it.password == "secret" }
    }

    @Test
    fun `correct credentials call the use case and leave navigation to the session`() = runTest {
        answers(Result.success(user))
        viewModel().testMvi {
            fill()
            intent(LoginIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.error == null && it.succeeded && it.password.isEmpty() }
            assertNoEffects()
        }
        coVerify(exactly = 1) { signIn("ana@example.test", "secret") }
    }

    private suspend fun finalStateAfter(failure: Throwable): LoginState {
        answers(Result.failure(failure))
        lateinit var last: LoginState
        viewModel().testMvi {
            fill()
            intent(LoginIntent.Submit)
            assertState { it.isLoading }
            assertState {
                last = it
                true
            }
        }
        return last
    }

    @Test
    fun `every failure except network and rate limit looks exactly like a wrong password`() = runTest {
        val wrongPassword = finalStateAfter(AuthFailure.InvalidCredentials)
        assertEquals(LoginError.INVALID_CREDENTIALS, wrongPassword.error)
        assertEquals("", wrongPassword.password)
        val others = listOf(
            AuthFailure.InvalidEmail,
            AuthFailure.UserNotFound,
            AuthFailure.AccountExistsWithOtherProvider,
            AuthFailure.ProviderUnavailable,
            AuthFailure.EmailAlreadyInUse,
            AuthFailure.WeakPassword,
            AuthFailure.RequiresRecentLogin,
            AuthFailure.Cancelled,
            AuthFailure.Unknown(IllegalStateException("disabled ana@example.test"))
        )
        for (failure in others) assertEquals(wrongPassword, finalStateAfter(failure), failure.toString())
    }

    @Test
    fun `network and rate limit failures keep their own messages and the typed password`() = runTest {
        val expected = mapOf(
            AuthFailure.Network to LoginError.NETWORK,
            AuthFailure.TooManyRequests to LoginError.TOO_MANY_REQUESTS
        )
        for ((failure, error) in expected) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                fill()
                intent(LoginIntent.Submit)
                assertState { it.isLoading }
                assertState { it.error == error && it.password == "secret" }
            }
        }
    }

    @Test
    fun `empty fields show field errors and never call the use case`() = runTest {
        viewModel().testMvi {
            intent(LoginIntent.Submit)
            assertState { it.emailError == LoginFieldError.EMAIL_EMPTY && !it.isLoading }
        }
        coVerify(exactly = 0) { signIn(any(), any()) }
    }

    @Test
    fun `a double tap on submit signs in once`() = runTest {
        answers(Result.success(user))
        viewModel().testMvi {
            fill()
            intent(LoginIntent.Submit)
            intent(LoginIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading }
        }
        coVerify(exactly = 1) { signIn(any(), any()) }
    }

    @Test
    fun `an exception from the use case becomes an error and the screen keeps working`() = runTest {
        coEvery { signIn(any(), any()) } throws IllegalStateException("boom ana@example.test")
        viewModel().testMvi {
            fill()
            intent(LoginIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == LoginError.INVALID_CREDENTIALS }
            answers(Result.success(user))
            intent(LoginIntent.PasswordChanged("secret"))
            assertState { it.password == "secret" }
            intent(LoginIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.error == null }
        }
    }
}
