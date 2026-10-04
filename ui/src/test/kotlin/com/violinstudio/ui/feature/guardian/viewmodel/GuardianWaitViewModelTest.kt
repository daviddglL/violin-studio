package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.repository.AuthRepository
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnUidUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.repository.ConsentRepository
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianWaitViewModelTest {
    private val request = mockk<RequestGuardianConsentUseCase>()
    private val pending = PendingGuardianEmail().also { it.remember("u1", "tutor@example.com") }
    private val ownEmail = mockk<GetOwnEmailUseCase> { coEvery { this@mockk() } returns "me@example.com" }
    private val ownUid = mockk<GetOwnUidUseCase> { coEvery { this@mockk() } returns "u1" }
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val receipt = Result.success(GuardianRequestReceipt("t***@example.com"))

    private fun viewModel(
        remembered: PendingGuardianEmail = pending,
        useCase: RequestGuardianConsentUseCase = request
    ) = GuardianWaitViewModel(useCase, remembered, ownEmail, ownUid, signOut, trigger)

    private fun answers(result: Result<GuardianRequestReceipt>) {
        coEvery { request(any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun WaitScenario.open() {
        intent(GuardianWaitIntent.SessionUpdated("t***@example.com", 1))
        assertState { it.emailMasked == "t***@example.com" && it.sends == 1 && it.canResend }
    }

    @Test
    fun `the session brings the masked email and resend is on only if the app remembers the email`() = runTest {
        viewModel().testMvi { open() }
        viewModel(PendingGuardianEmail()).testMvi {
            intent(GuardianWaitIntent.SessionUpdated("t***@example.com", 2))
            assertState { it.emailMasked == "t***@example.com" && it.sends == 2 && !it.canResend }
        }
    }

    @Test
    fun `an email remembered for another user is never offered`() = runTest {
        val other = PendingGuardianEmail().also { it.remember("u2", "otro@example.com") }
        viewModel(other).testMvi {
            intent(GuardianWaitIntent.SessionUpdated("t***@example.com", 1))
            assertState { !it.canResend }
        }
    }

    @Test
    fun `resend sends again to the remembered address once, confirms and bumps the count`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.notice == GuardianWaitNotice.RESENT && it.sends == 2 }
        }
        coVerify(exactly = 1) { request("tutor@example.com") }
    }

    @Test
    fun `a double resend is dropped`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.RESENT }
        }
        coVerify(exactly = 1) { request(any()) }
    }

    @Test
    fun `without a remembered email resend does nothing and only change email is left`() = runTest {
        viewModel(PendingGuardianEmail()).testMvi {
            intent(GuardianWaitIntent.SessionUpdated("t***@example.com", 1))
            assertState { !it.canResend }
            intent(GuardianWaitIntent.Resend)
            advanceUntilIdle()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `a rate limit with a wait disables resend until the wait elapses`() = runTest {
        answers(Result.failure(ConsentFailure.RateLimited(120)))
        val vm = viewModel()
        vm.testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState {
                it.error == GuardianRequestError.RATE_LIMITED && it.retryAfterSeconds == 120L && it.resendBlocked &&
                    !it.canResendNow
            }
            testScheduler.advanceTimeBy(119_000)
            testScheduler.runCurrent()
            assertTrue(vm.state.value.resendBlocked)
            testScheduler.advanceTimeBy(2_000)
            assertState { !it.resendBlocked && it.error == null && it.canResendNow }
        }
    }

    @Test
    fun `failures map to messages, never claim a resend and terminal ones turn resend off`() = runTest {
        val cases = listOf(
            ConsentFailure.NotMinor to GuardianRequestError.NOT_MINOR,
            ConsentFailure.Network to GuardianRequestError.NETWORK,
            ConsentFailure.Unknown() to GuardianRequestError.UNKNOWN,
            ConsentFailure.UnderageNotAllowed to GuardianRequestError.UNAVAILABLE,
            ConsentFailure.NoProfile to GuardianRequestError.UNAVAILABLE,
            ConsentFailure.EmailNotVerified to GuardianRequestError.UNAVAILABLE,
            ConsentFailure.InvalidArgument("policyVersion") to GuardianRequestError.UNKNOWN
        )
        for ((failure, error) in cases) {
            answers(Result.failure(failure))
            val terminal = error == GuardianRequestError.NOT_MINOR || error == GuardianRequestError.UNAVAILABLE
            viewModel().testMvi {
                open()
                intent(GuardianWaitIntent.Resend)
                assertState { it.isLoading }
                assertState { it.error == error && it.notice == null && !it.isLoading && it.canResend != terminal }
            }
        }
    }

    @Test
    fun `the server rejecting the remembered address on resend opens the change field with the error`() = runTest {
        val rejections = listOf(
            ConsentFailure.GuardianEmailInvalid,
            ConsentFailure.InvalidArgument("guardianEmail")
        )
        for (failure in rejections) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                open()
                intent(GuardianWaitIntent.Resend)
                assertState { it.isLoading }
                assertState {
                    it.changingEmail && it.emailError == GuardianEmailError.INVALID && !it.canResend && !it.isLoading
                }
            }
        }
    }

    @Test
    fun `a remembered address equal to the own email is rejected locally and opens the change field`() = runTest {
        val own = PendingGuardianEmail().also { it.remember("u1", "ME@example.com") }
        viewModel(own).testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.changingEmail && it.emailError == GuardianEmailError.OWN_EMAIL && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `already granted blocks sending, asks the session to resolve and can be checked again`() = runTest {
        answers(Result.failure(ConsentFailure.AlreadyGranted))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.ALREADY_APPROVED && !it.canResendNow }
            verify(exactly = 1) { trigger.requestRefresh() }
            intent(GuardianWaitIntent.CheckAgain)
            assertState { it.notice == null && it.canResendNow }
            verify(exactly = 2) { trigger.requestRefresh() }
        }
    }

    @Test
    fun `a new session update clears the already approved notice`() = runTest {
        answers(Result.failure(ConsentFailure.AlreadyGranted))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.ALREADY_APPROVED }
            intent(GuardianWaitIntent.SessionUpdated("t***@example.com", 3))
            assertState { it.notice == null && it.sends == 3 }
        }
    }

    @Test
    fun `change email goes through the real use case and the next resend uses the new remembered address`() = runTest {
        val consent = mockk<ConsentRepository>()
        coEvery { consent.requestGuardianConsent(any()) } coAnswers {
            delay(100)
            receipt
        }
        val auth = mockk<AuthRepository>()
        every { auth.authUser } returns flowOf(AuthUser("u1", "me@example.com", true, setOf(AuthProvider.PASSWORD)))
        val real = RequestGuardianConsentUseCase(consent, pending, auth)
        viewModel(useCase = real).testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("nuevo@example.com"))
            assertState { it.email == "nuevo@example.com" }
            intent(GuardianWaitIntent.SubmitNewEmail)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.EMAIL_CHANGED && !it.changingEmail && it.canResend }
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.RESENT }
        }
        coVerify(exactly = 2) { consent.requestGuardianConsent("nuevo@example.com") }
        coVerify(exactly = 0) { consent.requestGuardianConsent("tutor@example.com") }
        assertEquals("nuevo@example.com", pending.emailFor("u1"))
    }

    @Test
    fun `a malformed new email never reaches the server`() = runTest {
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("no-es-un-email"))
            assertState { it.email == "no-es-un-email" }
            intent(GuardianWaitIntent.SubmitNewEmail)
            assertState { it.emailError == GuardianEmailError.INVALID && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `the own email is rejected locally, normalised`() = runTest {
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("  ME@Example.COM "))
            assertState { it.email == "  ME@Example.COM " }
            intent(GuardianWaitIntent.SubmitNewEmail)
            assertState { it.isLoading }
            assertState { it.emailError == GuardianEmailError.OWN_EMAIL && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `the server rejecting the new address is a field error and the field stays open`() = runTest {
        answers(Result.failure(ConsentFailure.GuardianEmailInvalid))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("nuevo@example.com"))
            assertState { it.email == "nuevo@example.com" }
            intent(GuardianWaitIntent.SubmitNewEmail)
            assertState { it.isLoading }
            assertState { it.emailError == GuardianEmailError.INVALID && it.changingEmail && !it.isLoading }
        }
    }

    @Test
    fun `a rate limited change keeps the field and the typed address`() = runTest {
        answers(Result.failure(ConsentFailure.RateLimited(null)))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("nuevo@example.com"))
            assertState { it.email == "nuevo@example.com" }
            intent(GuardianWaitIntent.SubmitNewEmail)
            assertState { it.isLoading }
            assertState { it.error == GuardianRequestError.RATE_LIMITED && it.email == "nuevo@example.com" }
        }
    }

    @Test
    fun `while a send is pending sign out and the change field are ignored`() = runTest {
        answers(receipt)
        val vm = viewModel()
        vm.testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            intent(GuardianWaitIntent.SignOut)
            intent(GuardianWaitIntent.ChangeEmail)
            intent(GuardianWaitIntent.EmailChanged("x@y.zz"))
            intent(GuardianWaitIntent.CancelChangeEmail)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.RESENT }
            advanceUntilIdle()
        }
        val last = vm.state.value
        assertEquals(GuardianWaitNotice.RESENT, last.notice)
        assertFalse(last.changingEmail || last.isSigningOut)
        assertEquals("", last.email)
        coVerify(exactly = 0) { signOut() }
        coVerify(exactly = 1) { request(any()) }
    }

    @Test
    fun `while a sign out is pending the state says so and every other action is ignored`() = runTest {
        coEvery { signOut() } coAnswers { delay(100) }
        val vm = viewModel()
        vm.testMvi {
            open()
            intent(GuardianWaitIntent.SignOut)
            intent(GuardianWaitIntent.Resend)
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.isSigningOut && !it.canResendNow }
            assertState { !it.isSigningOut }
            advanceUntilIdle()
        }
        val last = vm.state.value
        assertFalse(last.changingEmail || last.isLoading)
        coVerify(exactly = 1) { signOut() }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `an unexpected exception becomes a message and a failing sign out does not kill the loop`() = runTest {
        coEvery { request(any()) } throws IllegalStateException("boom")
        coEvery { signOut() } coAnswers {
            delay(10)
            throw IllegalStateException("boom")
        }
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.error == GuardianRequestError.UNKNOWN && !it.isLoading }
            intent(GuardianWaitIntent.SignOut)
            assertState { it.isSigningOut }
            assertState { !it.isSigningOut }
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
        }
    }

    @Test
    fun `cancelling the change closes the field without calling the server`() = runTest {
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.CancelChangeEmail)
            assertState { !it.changingEmail }
        }
        assertEquals("tutor@example.com", pending.emailFor("u1"))
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `the typed address never appears in the view model state text`() = runTest {
        val vm = viewModel()
        vm.testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("nuevo@example.com"))
            assertState { it.email == "nuevo@example.com" }
        }
        assertTrue(!vm.state.value.toString().contains("example"))
    }

    @Test
    fun `changing the email shows the masked email the server answered, not the old one`() = runTest {
        answers(Result.success(GuardianRequestReceipt("n***@example.com")))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail }
            intent(GuardianWaitIntent.EmailChanged("otro@example.com"))
            assertState { it.email == "otro@example.com" }
            intent(GuardianWaitIntent.SubmitNewEmail)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.EMAIL_CHANGED && it.emailMasked == "n***@example.com" }
        }
    }

    @Test
    fun `while the shared delete flow is active resend, change, check again and sign out are dropped`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.DeleteActiveChanged(true))
            assertState { it.deleteActive && it.busy && !it.canResendNow }
            intent(GuardianWaitIntent.Resend)
            intent(GuardianWaitIntent.ChangeEmail)
            intent(GuardianWaitIntent.CheckAgain)
            intent(GuardianWaitIntent.SignOut)
            intent(GuardianWaitIntent.DeleteActiveChanged(false))
            assertState { !it.deleteActive && it.canResendNow && !it.changingEmail && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
        coVerify(exactly = 0) { trigger.requestRefresh() }
        coVerify(exactly = 0) { signOut() }
    }

    @Test
    fun `the rate limit wait still ends while the shared delete flow is active`() = runTest {
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.DeleteActiveChanged(true))
            assertState { it.deleteActive }
            intent(GuardianWaitIntent.RetryWaitElapsed)
            intent(GuardianWaitIntent.DeleteActiveChanged(false))
            assertState { !it.deleteActive && !it.resendBlocked }
        }
    }
}

private typealias WaitScenario = MviScenario<GuardianWaitState, GuardianWaitIntent, UiEffect>
