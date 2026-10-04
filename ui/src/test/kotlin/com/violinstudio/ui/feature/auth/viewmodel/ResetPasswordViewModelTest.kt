package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.usecase.SendPasswordResetUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ResetPasswordViewModelTest {
    private val reset = mockk<SendPasswordResetUseCase>()

    private fun viewModel() = ResetPasswordViewModel(reset)

    private fun answers(result: Result<Unit>) {
        coEvery { reset(any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun MviScenario<ResetPasswordState, ResetPasswordIntent, ResetPasswordEffect>.type(email: String) {
        intent(ResetPasswordIntent.EmailChanged(email))
        assertState { it.email == email }
    }

    @Test
    fun `a malformed email is a field error and never reaches the use case`() = runTest {
        viewModel().testMvi {
            type("not-an-email")
            intent(ResetPasswordIntent.Submit)
            assertState { it.emailError == ResetFieldError.EMAIL_INVALID && !it.isLoading && !it.sent }
        }
        coVerify(exactly = 0) { reset(any()) }
    }

    @Test
    fun `success shows the uniform confirmation`() = runTest {
        answers(Result.success(Unit))
        viewModel().testMvi {
            type("ana@example.test")
            intent(ResetPasswordIntent.Submit)
            assertState { it.isLoading }
            assertState { it.sent && !it.isLoading && it.error == null }
            assertNoEffects()
        }
        coVerify(exactly = 1) { reset("ana@example.test") }
    }

    @Test
    fun `network and rate limit failures are reported and never as sent`() = runTest {
        val expected = mapOf<AuthFailure, (ResetPasswordState) -> Boolean>(
            AuthFailure.Network to { it.error == ResetError.NETWORK && !it.sent },
            AuthFailure.TooManyRequests to { it.error == ResetError.TOO_MANY_REQUESTS && !it.sent },
            AuthFailure.Unknown() to { it.error == ResetError.UNKNOWN && !it.sent },
            AuthFailure.InvalidEmail to { it.emailError == ResetFieldError.EMAIL_INVALID && !it.sent }
        )
        for ((failure, check) in expected) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                type("ana@example.test")
                intent(ResetPasswordIntent.Submit)
                assertState { it.isLoading }
                assertState(check)
            }
        }
    }

    @Test
    fun `a double tap and an exception keep the screen usable`() = runTest {
        coEvery { reset(any()) } throws IllegalStateException("boom ana@example.test")
        viewModel().testMvi {
            type("ana@example.test")
            intent(ResetPasswordIntent.Submit)
            assertState { it.isLoading }
            assertState { it.error == ResetError.UNKNOWN }
            answers(Result.success(Unit))
            intent(ResetPasswordIntent.Submit)
            intent(ResetPasswordIntent.Submit)
            assertState { it.isLoading }
            assertState { it.sent }
        }
        coVerify(exactly = 2) { reset(any()) }
    }
}
