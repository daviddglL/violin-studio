package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.PendingGuardianEmail
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import com.violinstudio.ui.feature.consent.viewmodel.ConsentDeleteError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianWaitViewModelTest {
    private val request = mockk<RequestGuardianConsentUseCase>()
    private val pending = PendingGuardianEmail().also { it.remember("tutor@example.com") }
    private val ownEmail = mockk<GetOwnEmailUseCase> { coEvery { this@mockk() } returns "me@example.com" }
    private val delete = mockk<DeleteAccountUseCase>()
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val receipt = Result.success(GuardianRequestReceipt("t***@example.com"))

    private fun viewModel(remembered: PendingGuardianEmail = pending) =
        GuardianWaitViewModel(request, remembered, ownEmail, delete, signOut, trigger)

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
    fun `resend sends again to the remembered address once and confirms`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { !it.isLoading && it.notice == GuardianWaitNotice.RESENT && it.error == null }
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
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `rate limited resend shows the wait and can be retried`() = runTest {
        answers(Result.failure(ConsentFailure.RateLimited(120)))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState {
                it.error == GuardianRequestError.RATE_LIMITED && it.retryAfterSeconds == 120L && it.canResendNow
            }
        }
    }

    @Test
    fun `failures map to messages and never claim a resend`() = runTest {
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
            viewModel().testMvi {
                open()
                intent(GuardianWaitIntent.Resend)
                assertState { it.isLoading }
                assertState { it.error == error && it.notice == null && !it.isLoading }
            }
        }
    }

    @Test
    fun `already granted blocks sending, asks the session to resolve and shows the notice`() = runTest {
        answers(Result.failure(ConsentFailure.AlreadyGranted))
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.ALREADY_APPROVED && !it.canResendNow }
        }
        verify(exactly = 1) { trigger.requestRefresh() }
    }

    @Test
    fun `change email sends to the new address and the next resend uses it`() = runTest {
        coEvery { request("nuevo@example.com") } coAnswers {
            delay(100)
            pending.remember("nuevo@example.com")
            receipt
        }
        viewModel().testMvi {
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
        coVerify(exactly = 2) { request("nuevo@example.com") }
        coVerify(exactly = 0) { request("tutor@example.com") }
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
    fun `resend, change, delete and sign out exclude each other while one is pending`() = runTest {
        answers(receipt)
        coEvery { delete() } returns Result.success(Unit)
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            intent(GuardianWaitIntent.DeleteAccount)
            intent(GuardianWaitIntent.SignOut)
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.isLoading }
            assertState { it.notice == GuardianWaitNotice.RESENT && !it.changingEmail }
        }
        coVerify(exactly = 0) { delete() }
        coVerify(exactly = 0) { signOut() }
        coVerify(exactly = 1) { request(any()) }
    }

    @Test
    fun `delete and sign out block sending while pending`() = runTest {
        coEvery { delete() } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.DeleteAccount)
            intent(GuardianWaitIntent.Resend)
            assertState { it.isDeleting }
            assertState { !it.isDeleting && it.notice == null }
        }
        coVerify(exactly = 0) { request(any()) }
        coEvery { signOut() } coAnswers { delay(100) }
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.SignOut)
            intent(GuardianWaitIntent.Resend)
            intent(GuardianWaitIntent.DeleteAccount)
            intent(GuardianWaitIntent.ChangeEmail)
            assertState { it.changingEmail && !it.isLoading && !it.isDeleting }
        }
        coVerify(exactly = 1) { signOut() }
        coVerify(exactly = 0) { request(any()) }
        coVerify(exactly = 1) { delete() }
    }

    @Test
    fun `delete failures never claim the account was deleted`() = runTest {
        val cases = listOf(
            AccountFailure.RequiresRecentLogin to ConsentDeleteError.REAUTH_REQUIRED,
            AccountFailure.Network to ConsentDeleteError.NETWORK,
            AccountFailure.ErasureFailed to ConsentDeleteError.FAILED
        )
        for ((failure, error) in cases) {
            coEvery { delete() } returns Result.failure(failure)
            viewModel().testMvi {
                intent(GuardianWaitIntent.DeleteAccount)
                assertState { it.isDeleting }
                assertState { !it.isDeleting && it.deleteError == error }
            }
        }
    }

    @Test
    fun `an unexpected exception becomes a message and a failing sign out does not kill the loop`() = runTest {
        coEvery { request(any()) } throws IllegalStateException("boom")
        coEvery { signOut() } throws IllegalStateException("boom")
        viewModel().testMvi {
            open()
            intent(GuardianWaitIntent.Resend)
            assertState { it.isLoading }
            assertState { it.error == GuardianRequestError.UNKNOWN && !it.isLoading }
            intent(GuardianWaitIntent.SignOut)
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
        assertEquals("tutor@example.com", pending.email)
        coVerify(exactly = 0) { request(any()) }
    }
}

private typealias WaitScenario = MviScenario<GuardianWaitState, GuardianWaitIntent, UiEffect>
