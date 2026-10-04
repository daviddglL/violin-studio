package com.violinstudio.ui.feature.account.viewmodel

import com.violinstudio.domain.feature.account.failure.AccountFailure
import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase
import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.usecase.GetReauthMethodUseCase
import com.violinstudio.domain.feature.auth.usecase.ReauthMethod
import com.violinstudio.domain.feature.auth.usecase.ReauthenticateUseCase
import com.violinstudio.ui.commons.mvi.UiEffect
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

private typealias Flow = MviScenario<DeleteAccountState, DeleteAccountIntent, UiEffect>

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {
    private val delete = mockk<DeleteAccountUseCase>()
    private val reauth = mockk<ReauthenticateUseCase>()
    private val method = mockk<GetReauthMethodUseCase>()
    private val token = GoogleIdToken("google-token")

    private fun viewModel() = DeleteAccountViewModel(delete, reauth, method)

    /** Primera llamada -> [first]; las siguientes -> [then] (o [first] si no se da). */
    private fun deleteAnswers(first: Result<Unit>, then: Result<Unit> = first) {
        var calls = 0
        coEvery { delete() } coAnswers {
            delay(100)
            if (calls++ == 0) first else then
        }
    }

    private suspend fun Flow.openAndConfirm() {
        intent(DeleteAccountIntent.Open)
        assertState { it.step == DeleteStep.CONFIRMING }
        intent(DeleteAccountIntent.Confirm)
        assertState { it.isWorking }
    }

    private suspend fun Flow.typePassword(value: String) {
        intent(DeleteAccountIntent.PasswordChanged(value))
        assertState { it.password == value }
    }

    @Test
    fun `opening only asks for confirmation and calls no callable`() = runTest {
        viewModel().testMvi {
            intent(DeleteAccountIntent.Open)
            assertState { it.step == DeleteStep.CONFIRMING && !it.isWorking }
        }
        coVerify(exactly = 0) { delete() }
        coVerify(exactly = 0) { reauth(any<String>()) }
    }

    @Test
    fun `cancelling never calls deleteAccount or reauthenticate`() = runTest {
        viewModel().testMvi {
            intent(DeleteAccountIntent.Open)
            assertState { it.step == DeleteStep.CONFIRMING }
            intent(DeleteAccountIntent.Cancel)
            assertState { it.step == DeleteStep.IDLE }
        }
        coVerify(exactly = 0) { delete() }
        coVerify(exactly = 0) { reauth(any<String>()) }
    }

    @Test
    fun `success keeps everything locked until the session changes`() = runTest {
        deleteAnswers(Result.success(Unit))
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.deleted && !it.isWorking && it.error == null && !it.canConfirm && !it.canOpen }
            assertNoEffects()
        }
    }

    @Test
    fun `a double confirm calls deleteAccount once`() = runTest {
        deleteAnswers(Result.success(Unit))
        viewModel().testMvi {
            intent(DeleteAccountIntent.Open)
            assertState { it.step == DeleteStep.CONFIRMING }
            intent(DeleteAccountIntent.Confirm)
            intent(DeleteAccountIntent.Confirm)
            assertState { it.isWorking }
            assertState { it.deleted }
        }
        coVerify(exactly = 1) { delete() }
    }

    @Test
    fun `terminal and retryable failures map explicitly and never claim deletion`() = runTest {
        val cases = listOf(
            AccountFailure.Network to DeleteAccountError.NETWORK,
            AccountFailure.ErasureFailed to DeleteAccountError.FAILED,
            AccountFailure.Unauthenticated to DeleteAccountError.FAILED,
            AccountFailure.Unknown() to DeleteAccountError.FAILED
        )
        for ((failure, error) in cases) {
            deleteAnswers(Result.failure(failure))
            viewModel().testMvi {
                openAndConfirm()
                assertState { !it.isWorking && it.error == error && !it.deleted && it.canConfirm }
            }
        }
    }

    @Test
    fun `an exception from the use case becomes a retryable failure`() = runTest {
        coEvery { delete() } coAnswers {
            delay(100)
            throw IllegalStateException("boom")
        }
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.error == DeleteAccountError.FAILED && it.canConfirm }
        }
    }

    @Test
    fun `retry after a network error with the session still active deletes`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.Network), Result.success(Unit))
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.error == DeleteAccountError.NETWORK }
            intent(DeleteAccountIntent.Confirm)
            assertState { it.isWorking && it.error == null }
            assertState { it.deleted }
        }
        coVerify(exactly = 2) { delete() }
    }

    @Test
    fun `a recent login requirement on a password account asks for the password and does not retry by itself`() =
        runTest {
            deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin))
            coEvery { method() } returns ReauthMethod.PASSWORD
            viewModel().testMvi {
                openAndConfirm()
                assertState { it.step == DeleteStep.REAUTH && it.method == ReauthMethod.PASSWORD && !it.isWorking }
            }
            coVerify(exactly = 1) { delete() }
        }

    @Test
    fun `the right password reauthenticates and then deletes`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin), Result.success(Unit))
        coEvery { method() } returns ReauthMethod.PASSWORD
        coEvery { reauth("hunter2") } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.step == DeleteStep.REAUTH }
            typePassword("hunter2")
            intent(DeleteAccountIntent.SubmitPassword)
            assertState { it.isWorking }
            assertState { it.deleted }
        }
        coVerify(exactly = 2) { delete() }
    }

    @Test
    fun `a wrong password shows the error and never calls deleteAccount again`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin))
        coEvery { method() } returns ReauthMethod.PASSWORD
        coEvery { reauth("bad") } coAnswers {
            delay(100)
            Result.failure(AuthFailure.InvalidCredentials)
        }
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.step == DeleteStep.REAUTH }
            typePassword("bad")
            intent(DeleteAccountIntent.SubmitPassword)
            assertState { it.isWorking }
            assertState {
                !it.isWorking && it.error == DeleteAccountError.WRONG_PASSWORD && it.password.isEmpty() &&
                    it.step == DeleteStep.REAUTH
            }
        }
        coVerify(exactly = 1) { delete() }
    }

    @Test
    fun `reauth failures map explicitly and keep the password for a retry`() = runTest {
        val cases = listOf(
            AuthFailure.TooManyRequests to DeleteAccountError.TOO_MANY_ATTEMPTS,
            AuthFailure.Network to DeleteAccountError.NETWORK,
            AuthFailure.ProviderUnavailable to DeleteAccountError.PROVIDER_UNAVAILABLE,
            AuthFailure.Unknown() to DeleteAccountError.FAILED
        )
        for ((failure, error) in cases) {
            deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin))
            coEvery { method() } returns ReauthMethod.PASSWORD
            coEvery { reauth("pw") } coAnswers {
                delay(100)
                Result.failure(failure)
            }
            viewModel().testMvi {
                openAndConfirm()
                assertState { it.step == DeleteStep.REAUTH }
                typePassword("pw")
                intent(DeleteAccountIntent.SubmitPassword)
                assertState { it.isWorking }
                assertState { it.error == error && it.password == "pw" && it.canSubmitPassword }
            }
        }
    }

    @Test
    fun `a blank password never reaches reauthenticate`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin))
        coEvery { method() } returns ReauthMethod.PASSWORD
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.step == DeleteStep.REAUTH }
            intent(DeleteAccountIntent.SubmitPassword)
            intent(DeleteAccountIntent.PasswordChanged("x"))
            assertState { it.password == "x" && !it.isWorking }
        }
        coVerify(exactly = 0) { reauth(any<String>()) }
    }

    @Test
    fun `a google account reauthenticates with the google token and then deletes`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin), Result.success(Unit))
        coEvery { method() } returns ReauthMethod.GOOGLE
        coEvery { reauth(token) } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.step == DeleteStep.REAUTH && it.canReauthWithGoogle }
            intent(DeleteAccountIntent.GoogleToken(token))
            assertState { it.isWorking }
            assertState { it.deleted }
        }
        coVerify(exactly = 0) { reauth(any<String>()) }
        coVerify(exactly = 2) { delete() }
    }

    @Test
    fun `an unavailable google provider is retryable and a token outside the reauth step is ignored`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin))
        coEvery { method() } returns ReauthMethod.GOOGLE
        viewModel().testMvi {
            intent(DeleteAccountIntent.GoogleToken(token))
            openAndConfirm()
            assertState { it.step == DeleteStep.REAUTH }
            intent(DeleteAccountIntent.GoogleFailed)
            assertState { it.error == DeleteAccountError.PROVIDER_UNAVAILABLE && it.canReauthWithGoogle }
        }
        coVerify(exactly = 0) { reauth(any<GoogleIdToken>()) }
    }

    @Test
    fun `a delete failure after a fresh reauth goes back to confirmation without asking for the password`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin), Result.failure(AccountFailure.Network))
        coEvery { method() } returns ReauthMethod.PASSWORD
        coEvery { reauth("pw") } returns Result.success(Unit)
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.step == DeleteStep.REAUTH }
            typePassword("pw")
            intent(DeleteAccountIntent.SubmitPassword)
            assertState { it.isWorking }
            assertState { it.step == DeleteStep.CONFIRMING && it.error == DeleteAccountError.NETWORK && it.canConfirm }
        }
    }

    @Test
    fun `an account with no provider to reauthenticate gets a terminal message and cannot retry`() = runTest {
        deleteAnswers(Result.failure(AccountFailure.RequiresRecentLogin))
        coEvery { method() } returns ReauthMethod.NONE
        viewModel().testMvi {
            openAndConfirm()
            assertState { it.error == DeleteAccountError.REAUTH_UNAVAILABLE && !it.canConfirm && !it.isWorking }
        }
        coVerify(exactly = 1) { delete() }
    }

    @Test
    fun `cancel while the deletion runs is dropped`() = runTest {
        deleteAnswers(Result.success(Unit))
        viewModel().testMvi {
            openAndConfirm()
            intent(DeleteAccountIntent.Cancel)
            assertState { it.deleted }
        }
        coVerify(exactly = 1) { delete() }
    }
}
