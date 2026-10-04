package com.violinstudio.ui.feature.auth.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.usecase.CheckEmailVerifiedUseCase
import com.violinstudio.domain.feature.auth.usecase.SendEmailVerificationUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyEmailViewModelTest {
    private val check = mockk<CheckEmailVerifiedUseCase>()
    private val send = mockk<SendEmailVerificationUseCase>()
    private val signOut = mockk<SignOutUseCase>()

    private fun viewModel() = VerifyEmailViewModel(check, send, signOut)

    // The cooldown timer is a viewModelScope job: cancel it so runTest can finish.
    private suspend fun VerifyEmailViewModel.scenario(
        block: suspend MviScenario<VerifyEmailState, VerifyEmailIntent, VerifyEmailEffect>.() -> Unit
    ) {
        try {
            testMvi(block)
        } finally {
            viewModelScope.cancel()
        }
    }

    private fun checkReturns(result: Result<Boolean>) {
        coEvery { check() } coAnswers {
            delay(100)
            result
        }
    }

    @Test
    fun `a verified check keeps feedback and blocks actions until the session replaces the screen`() = runTest {
        checkReturns(Result.success(true))
        viewModel().scenario {
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.verified && it.message == VerifyEmailMessage.VERIFIED_CONTINUING && !it.canCheck }
            intent(VerifyEmailIntent.Resend)
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            coVerify(exactly = 1) { check() }
            coVerify(exactly = 0) { send() }
        }
    }

    @Test
    fun `if the screen is still shown 10 seconds after verifying it falls back and re-enables`() = runTest {
        checkReturns(Result.success(true))
        viewModel().scenario {
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.verified }
            testScheduler.advanceTimeBy(9_000)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1_100)
            testScheduler.runCurrent()
            assertState { !it.verified && it.canCheck && it.message == VerifyEmailMessage.UNKNOWN }
        }
    }

    @Test
    fun `two quick CheckNow taps produce one server call`() = runTest {
        checkReturns(Result.success(false))
        viewModel().scenario {
            intent(VerifyEmailIntent.CheckNow)
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.message == VerifyEmailMessage.NOT_VERIFIED_YET }
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            coVerify(exactly = 1) { check() }
        }
    }

    @Test
    fun `two quick Resend taps produce one send`() = runTest {
        coEvery { send() } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().scenario {
            intent(VerifyEmailIntent.Resend)
            intent(VerifyEmailIntent.Resend)
            assertState { it.resendCooldownSeconds == 60 }
            coVerify(exactly = 1) { send() }
        }
    }

    @Test
    fun `CheckNow during a too-many-requests block keeps the wait error`() = runTest {
        coEvery { send() } returns Result.failure(AuthFailure.TooManyRequests)
        checkReturns(Result.success(false))
        viewModel().scenario {
            intent(VerifyEmailIntent.Resend)
            assertState { it.message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS }
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking && it.message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS }
            assertState { !it.checking && it.message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS }
        }
    }

    @Test
    fun `CheckNow while still unverified informs and stays`() = runTest {
        checkReturns(Result.success(false))
        viewModel().scenario {
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.message == VerifyEmailMessage.NOT_VERIFIED_YET && !it.checking }
        }
    }

    @Test
    fun `CheckNow maps a network failure and any other failure`() = runTest {
        checkReturns(Result.failure(AuthFailure.Network))
        viewModel().scenario {
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.message == VerifyEmailMessage.NETWORK }
            checkReturns(Result.failure(AuthFailure.Unknown()))
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.message == VerifyEmailMessage.UNKNOWN }
        }
    }

    @Test
    fun `a throwing check degrades to a message and leaves the view model responsive`() = runTest {
        coEvery { check() } throws IllegalStateException("boom")
        viewModel().scenario {
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.message == VerifyEmailMessage.UNKNOWN && !it.checking }
            checkReturns(Result.success(false))
            intent(VerifyEmailIntent.CheckNow)
            assertState { it.checking }
            assertState { it.message == VerifyEmailMessage.NOT_VERIFIED_YET }
        }
    }

    @Test
    fun `Resend starts a 60 second cooldown that counts down in virtual time and then re-enables`() = runTest {
        coEvery { send() } returns Result.success(Unit)
        viewModel().scenario {
            intent(VerifyEmailIntent.Resend)
            assertState { it.resendCooldownSeconds == 60 && it.message == VerifyEmailMessage.RESEND_SENT }
            testScheduler.advanceTimeBy(1_001)
            assertState { it.resendCooldownSeconds == 59 }
            for (remaining in 58 downTo 0) {
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
                assertState { it.resendCooldownSeconds == remaining }
            }
        }
    }

    @Test
    fun `Resend during the cooldown does not call the use case again`() = runTest {
        coEvery { send() } returns Result.success(Unit)
        viewModel().scenario {
            intent(VerifyEmailIntent.Resend)
            assertState { it.resendCooldownSeconds == 60 }
            intent(VerifyEmailIntent.Resend)
            testScheduler.advanceTimeBy(30_000)
            testScheduler.runCurrent()
            coVerify(exactly = 1) { send() }
            testScheduler.advanceTimeBy(30_000)
            testScheduler.runCurrent()
            intent(VerifyEmailIntent.Resend)
            testScheduler.runCurrent()
            coVerify(exactly = 2) { send() }
        }
    }

    @Test
    fun `too many requests shows a wait error and never retries by itself`() = runTest {
        coEvery { send() } returns Result.failure(AuthFailure.TooManyRequests)
        viewModel().scenario {
            intent(VerifyEmailIntent.Resend)
            assertState {
                it.message == VerifyEmailMessage.WAIT_TOO_MANY_REQUESTS && it.resendCooldownSeconds == 60
            }
            testScheduler.advanceTimeBy(120_000)
            testScheduler.runCurrent()
            coVerify(exactly = 1) { send() }
        }
    }

    @Test
    fun `a network failure on resend reports it and allows trying again at once`() = runTest {
        coEvery { send() } returns Result.failure(AuthFailure.Network)
        viewModel().scenario {
            intent(VerifyEmailIntent.Resend)
            assertState { it.message == VerifyEmailMessage.NETWORK && it.canResend }
            intent(VerifyEmailIntent.Resend)
            testScheduler.runCurrent()
            coVerify(exactly = 2) { send() }
        }
    }

    @Test
    fun `SignOut calls the use case and a throwing sign-out leaves the view model responsive`() = runTest {
        coEvery { signOut() } throws IllegalStateException("boom") andThen Unit
        viewModel().scenario {
            intent(VerifyEmailIntent.SignOut)
            testScheduler.runCurrent()
            intent(VerifyEmailIntent.SignOut)
            testScheduler.runCurrent()
            coVerify(exactly = 2) { signOut() }
        }
    }

    @Test
    fun `while the shared delete flow is active check and resend are dropped`() = runTest {
        checkReturns(Result.success(false))
        coEvery { send() } returns Result.success(Unit)
        viewModel().scenario {
            intent(VerifyEmailIntent.DeleteActiveChanged(true))
            assertState { it.deleteActive && !it.canCheck && !it.canResend }
            intent(VerifyEmailIntent.CheckNow)
            intent(VerifyEmailIntent.Resend)
            intent(VerifyEmailIntent.DeleteActiveChanged(false))
            assertState { !it.deleteActive && it.canCheck && it.canResend && it.message == null }
        }
        coVerify(exactly = 0) { check() }
        coVerify(exactly = 0) { send() }
    }
}
