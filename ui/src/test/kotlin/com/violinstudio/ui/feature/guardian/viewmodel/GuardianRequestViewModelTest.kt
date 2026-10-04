package com.violinstudio.ui.feature.guardian.viewmodel

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.usecase.GetOwnEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.GuardianRequestReceipt
import com.violinstudio.domain.feature.consent.usecase.RequestGuardianConsentUseCase
import com.violinstudio.domain.feature.session.ConsentReason
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianRequestViewModelTest {
    private val request = mockk<RequestGuardianConsentUseCase>()
    private val ownEmail = mockk<GetOwnEmailUseCase> { coEvery { this@mockk() } returns "me@example.com" }
    private val delete = mockk<DeleteAccountUseCase>()
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val receipt = Result.success(GuardianRequestReceipt("t***@example.com"))

    private fun viewModel() = GuardianRequestViewModel(request, ownEmail, delete, signOut, trigger)

    private fun answers(result: Result<GuardianRequestReceipt>) {
        coEvery { request(any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun Scenario.type(email: String = "tutor@example.com") {
        intent(GuardianRequestIntent.EmailChanged(email))
        assertState { it.email == email }
    }

    @Test
    fun `a valid email calls the use case once, succeeds and the session decides the next screen`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.succeeded && it.error == null && it.emailError == null }
            assertNoEffects()
        }
        coVerify(exactly = 1) { request("tutor@example.com") }
    }

    @Test
    fun `a malformed email never reaches the use case`() = runTest {
        viewModel().testMvi {
            type("no-es-un-email")
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.emailError == GuardianEmailError.INVALID && !it.isLoading }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `a double submit is dropped`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        coVerify(exactly = 1) { request(any()) }
    }

    @Test
    fun `the server rejecting the address is a field error and the user can fix it`() = runTest {
        answers(Result.failure(ConsentFailure.GuardianEmailInvalid))
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.emailError == GuardianEmailError.INVALID && !it.isLoading && !it.succeeded }
            answers(receipt)
            type("otro@example.com")
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
    }

    @Test
    fun `rate limited shows the wait and allows trying again`() = runTest {
        answers(Result.failure(ConsentFailure.RateLimited(90)))
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState {
                it.error == GuardianRequestError.RATE_LIMITED && it.retryAfterSeconds == 90L && it.canSubmit
            }
        }
    }

    @Test
    fun `other failures map to a message and never advance`() = runTest {
        val cases = listOf(
            ConsentFailure.NotMinor to GuardianRequestError.NOT_MINOR,
            ConsentFailure.Network to GuardianRequestError.NETWORK,
            ConsentFailure.Unknown() to GuardianRequestError.UNKNOWN,
            ConsentFailure.UnderageNotAllowed to GuardianRequestError.UNAVAILABLE,
            ConsentFailure.NoProfile to GuardianRequestError.UNAVAILABLE,
            ConsentFailure.EmailNotVerified to GuardianRequestError.UNAVAILABLE,
            ConsentFailure.InvalidArgument("policyVersion") to GuardianRequestError.UNKNOWN,
            ConsentFailure.InvalidArgument(null) to GuardianRequestError.UNKNOWN,
            ConsentFailure.RateLimited(null) to GuardianRequestError.RATE_LIMITED
        )
        for ((failure, error) in cases) {
            answers(Result.failure(failure))
            viewModel().testMvi {
                type()
                intent(GuardianRequestIntent.SubmitGuardianEmail)
                assertState { it.isLoading }
                assertState { it.error == error && !it.succeeded && it.retryAfterSeconds == null }
            }
        }
    }

    @Test
    fun `already granted counts as success and asks the session to resolve again`() = runTest {
        answers(Result.failure(ConsentFailure.AlreadyGranted))
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.succeeded && it.alreadyApproved && it.error == null }
        }
        verify(exactly = 1) { trigger.requestRefresh() }
    }

    @Test
    fun `a plain success is not flagged as already approved`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.succeeded && !it.alreadyApproved }
        }
    }

    @Test
    fun `an invalid argument on the guardian email is a field error`() = runTest {
        answers(Result.failure(ConsentFailure.InvalidArgument("guardianEmail")))
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.emailError == GuardianEmailError.INVALID && it.error == null && !it.isLoading }
        }
    }

    @Test
    fun `the own email, normalised, is rejected locally without calling the server`() = runTest {
        viewModel().testMvi {
            type("  ME@Example.COM ")
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.emailError == GuardianEmailError.OWN_EMAIL && !it.isLoading && !it.succeeded }
        }
        coVerify(exactly = 0) { request(any()) }
    }

    @Test
    fun `without a known own email the request goes ahead`() = runTest {
        coEvery { ownEmail() } returns null
        answers(receipt)
        viewModel().testMvi {
            type("me@example.com")
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
    }

    @Test
    fun `sign out excludes submit and delete while pending, and vice versa`() = runTest {
        answers(receipt)
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            intent(GuardianRequestIntent.SignOut)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        coVerify(exactly = 0) { signOut() }
        coEvery { signOut() } coAnswers { delay(100) }
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SignOut)
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            intent(GuardianRequestIntent.DeleteAccount)
            intent(GuardianRequestIntent.EmailChanged("otro@example.com"))
            assertState { it.email == "otro@example.com" && !it.isLoading && !it.isDeleting }
        }
        coVerify(exactly = 1) { signOut() }
        coVerify(exactly = 1) { request(any()) }
        coVerify(exactly = 0) { delete() }
    }

    @Test
    fun `an unexpected exception becomes a message and the loop survives`() = runTest {
        coEvery { request(any()) } throws IllegalStateException("boom")
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            assertState { it.isLoading }
            assertState { it.error == GuardianRequestError.UNKNOWN && !it.isLoading }
            type("otro@example.com")
        }
    }

    @Test
    fun `the session reason from a bump or a revocation is kept`() = runTest {
        viewModel().testMvi {
            intent(GuardianRequestIntent.SessionUpdated(ConsentReason.REVOKED))
            assertState { it.reason == ConsentReason.REVOKED }
            intent(GuardianRequestIntent.SessionUpdated(ConsentReason.POLICY_UPDATED))
            assertState { it.reason == ConsentReason.POLICY_UPDATED }
        }
    }

    @Test
    fun `delete account success shows no message because the session changes`() = runTest {
        coEvery { delete() } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            intent(GuardianRequestIntent.DeleteAccount)
            assertState { it.isDeleting }
            assertState { !it.isDeleting && it.deleteError == null }
        }
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
                intent(GuardianRequestIntent.DeleteAccount)
                assertState { it.isDeleting }
                assertState { !it.isDeleting && it.deleteError == error }
            }
        }
    }

    @Test
    fun `submit and delete exclude each other while one is pending`() = runTest {
        answers(receipt)
        coEvery { delete() } returns Result.success(Unit)
        viewModel().testMvi {
            type()
            intent(GuardianRequestIntent.SubmitGuardianEmail)
            intent(GuardianRequestIntent.DeleteAccount)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        coVerify(exactly = 0) { delete() }
    }

    @Test
    fun `sign out calls the use case and a failure does not kill the loop`() = runTest {
        coEvery { signOut() } throws IllegalStateException("boom")
        viewModel().testMvi {
            intent(GuardianRequestIntent.SignOut)
            type()
        }
        coVerify(exactly = 1) { signOut() }
    }
}

private typealias Scenario = MviScenario<GuardianRequestState, GuardianRequestIntent, UiEffect>
