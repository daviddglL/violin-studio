package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.usecase.SignInWithEmailUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
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

    private fun viewModel() = LoginViewModel(signIn)

    private fun answers(result: Result<AuthUser>) {
        coEvery { signIn(any(), any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun com.violinstudio.ui.commons.testing.MviScenario<LoginState, LoginIntent, LoginEffect>
    .fill() {
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
            assertState { !it.isLoading && it.error == null }
            assertNoEffects()
        }
        coVerify(exactly = 1) { signIn("ana@example.test", "secret") }
    }

    @Test
    fun `wrong password and unknown account show the same message`() = runTest {
        val shown = mutableListOf<LoginError?>()
        for (failure in listOf(AuthFailure.InvalidCredentials, AuthFailure.InvalidEmail)) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                fill()
                intent(LoginIntent.Submit)
                assertState { it.isLoading }
                assertState { shown += it.error; true }
            }
        }
        assertEquals(listOf(LoginError.INVALID_CREDENTIALS, LoginError.INVALID_CREDENTIALS), shown)
    }

    @Test
    fun `network, rate limit and unexpected failures map to their own messages`() = runTest {
        val expected = mapOf(
            AuthFailure.Network to LoginError.NETWORK,
            AuthFailure.TooManyRequests to LoginError.TOO_MANY_REQUESTS,
            AuthFailure.Unknown() to LoginError.UNKNOWN
        )
        for ((failure, error) in expected) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                fill()
                intent(LoginIntent.Submit)
                assertState { it.isLoading }
                assertState { it.error == error }
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
            assertState { it.error == LoginError.UNKNOWN }
            answers(Result.success(user))
            intent(LoginIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.error == null }
        }
    }
}
