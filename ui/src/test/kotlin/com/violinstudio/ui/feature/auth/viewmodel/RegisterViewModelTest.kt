package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.usecase.SignUpWithEmailUseCase
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
class RegisterViewModelTest {
    private val signUp = mockk<SignUpWithEmailUseCase>()
    private val user = AuthUser("uid", "ana@example.test", false, setOf(AuthProvider.PASSWORD))

    private fun viewModel() = RegisterViewModel(signUp)

    private fun answers(result: Result<AuthUser>) {
        coEvery { signUp(any(), any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun MviScenario<RegisterState, RegisterIntent, RegisterEffect>.fill() {
        intent(RegisterIntent.EmailChanged("ana@example.test"))
        assertState { it.email == "ana@example.test" }
        intent(RegisterIntent.PasswordChanged("secret1"))
        assertState { it.password == "secret1" }
    }

    @Test
    fun `a successful sign up stops loading and leaves the session to move to email verification`() = runTest {
        answers(Result.success(user))
        viewModel().testMvi {
            fill()
            intent(RegisterIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.error == null && it.emailError == null && it.passwordError == null }
            assertNoEffects()
        }
        coVerify(exactly = 1) { signUp("ana@example.test", "secret1") }
    }

    @Test
    fun `an email already in use gives the generic message and no extra data`() = runTest {
        val shown = mutableListOf<RegisterError?>()
        for (failure in listOf(AuthFailure.EmailAlreadyInUse, AuthFailure.AccountExistsWithOtherProvider)) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                fill()
                intent(RegisterIntent.Submit)
                assertState { it.isLoading }
                assertState { shown += it.error; it.emailError == null && it.passwordError == null }
            }
        }
        assertEquals(listOf(RegisterError.ACCOUNT_UNAVAILABLE, RegisterError.ACCOUNT_UNAVAILABLE), shown)
    }

    @Test
    fun `a weak password marks the password field and does not change the session`() = runTest {
        answers(Result.failure(AuthFailure.WeakPassword))
        viewModel().testMvi {
            fill()
            intent(RegisterIntent.Submit)
            assertState { it.isLoading }
            assertState { it.passwordError == RegisterFieldError.PASSWORD_WEAK && it.error == null }
            assertNoEffects()
        }
    }

    @Test
    fun `an invalid email from the server marks the email field and other failures map to messages`() = runTest {
        val expected = mapOf<AuthFailure, (RegisterState) -> Boolean>(
            AuthFailure.InvalidEmail to { it.emailError == RegisterFieldError.EMAIL_INVALID },
            AuthFailure.Network to { it.error == RegisterError.NETWORK },
            AuthFailure.TooManyRequests to { it.error == RegisterError.TOO_MANY_REQUESTS },
            AuthFailure.Unknown() to { it.error == RegisterError.UNKNOWN }
        )
        for ((failure, check) in expected) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                fill()
                intent(RegisterIntent.Submit)
                assertState { it.isLoading }
                assertState(check)
            }
        }
    }

    @Test
    fun `invalid local input never calls the use case`() = runTest {
        viewModel().testMvi {
            intent(RegisterIntent.EmailChanged("no-at"))
            assertState { it.email == "no-at" }
            intent(RegisterIntent.Submit)
            assertState { it.emailError == RegisterFieldError.EMAIL_INVALID && !it.isLoading }
        }
        coVerify(exactly = 0) { signUp(any(), any()) }
    }

    @Test
    fun `a double tap and an exception both keep the screen usable`() = runTest {
        coEvery { signUp(any(), any()) } throws IllegalStateException("boom ana@example.test")
        viewModel().testMvi {
            fill()
            intent(RegisterIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == RegisterError.UNKNOWN }
            answers(Result.success(user))
            intent(RegisterIntent.Submit)
            intent(RegisterIntent.Submit)
            assertState { it.isLoading }
            assertState { !it.isLoading }
        }
        coVerify(exactly = 2) { signUp(any(), any()) }
    }
}
