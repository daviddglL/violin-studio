package com.violinstudio.ui.feature.auth.viewmodel

import com.violinstudio.domain.feature.auth.failure.AuthFailure
import com.violinstudio.domain.feature.auth.model.AuthProvider
import com.violinstudio.domain.feature.auth.model.AuthUser
import com.violinstudio.domain.feature.auth.model.GoogleIdToken
import com.violinstudio.domain.feature.auth.usecase.SignInWithEmailUseCase
import com.violinstudio.domain.feature.auth.usecase.SignInWithGoogleUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
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
class LoginGoogleViewModelTest {
    private val signInEmail = mockk<SignInWithEmailUseCase>()
    private val signInGoogle = mockk<SignInWithGoogleUseCase>()
    private val token = GoogleIdToken("google-jwt")
    private val googleUser = AuthUser("uid", "ana@gmail.test", true, setOf(AuthProvider.GOOGLE))

    private fun viewModel() = LoginViewModel(signInEmail, signInGoogle)

    private fun googleAnswers(result: Result<AuthUser?>) {
        coEvery { signInGoogle(any()) } coAnswers {
            delay(100)
            result
        }
    }

    @Test
    fun `a Google token signs in through the use case and leaves navigation to the session`() = runTest {
        googleAnswers(Result.success(googleUser))
        viewModel().testMvi {
            intent(LoginIntent.GoogleTokenReceived(token))
            assertState { it.isLoading }
            assertState { !it.isLoading && it.error == null && it.succeeded }
            assertNoEffects()
        }
        coVerify(exactly = 1) { signInGoogle(token) }
    }

    @Test
    fun `a cancelled Google sign-in shows no error and changes nothing`() = runTest {
        googleAnswers(Result.success(null))
        viewModel().testMvi {
            intent(LoginIntent.EmailChanged("ana@example.test"))
            assertState { it.email == "ana@example.test" }
            intent(LoginIntent.GoogleTokenReceived(token))
            assertState { it.isLoading }
            assertState { it == LoginState(email = "ana@example.test") }
        }
    }

    @Test
    fun `Google unavailable shows its own message and the email form keeps working`() = runTest {
        viewModel().testMvi {
            intent(LoginIntent.EmailChanged("ana@example.test"))
            assertState { it.email == "ana@example.test" }
            intent(LoginIntent.PasswordChanged("secret"))
            assertState { it.password == "secret" }
            intent(LoginIntent.GoogleFailed)
            assertState {
                it.error == LoginError.GOOGLE_UNAVAILABLE && it.canSubmit && it.password == "secret" &&
                    it.email == "ana@example.test"
            }
            coEvery { signInEmail(any(), any()) } returns Result.success(googleUser)
            intent(LoginIntent.Submit)
            assertState { it.isLoading && it.error == null }
        }
    }

    @Test
    fun `a use case failure maps to its own guided message without linking accounts`() = runTest {
        val expected = mapOf(
            AuthFailure.AccountExistsWithOtherProvider to LoginError.ACCOUNT_EXISTS_OTHER_PROVIDER,
            AuthFailure.ProviderUnavailable to LoginError.GOOGLE_UNAVAILABLE,
            AuthFailure.Network to LoginError.NETWORK,
            AuthFailure.TooManyRequests to LoginError.TOO_MANY_REQUESTS,
            AuthFailure.Unknown(IllegalStateException("x")) to LoginError.GOOGLE_UNAVAILABLE
        )
        for ((failure, error) in expected) {
            googleAnswers(Result.failure(failure))
            viewModel().testMvi {
                intent(LoginIntent.GoogleTokenReceived(token))
                assertState { it.isLoading }
                assertState { it.error == error && !it.isLoading && it.canSubmit }
            }
        }
        coVerify(exactly = 0) { signInEmail(any(), any()) }
    }

    @Test
    fun `an exception from the Google use case becomes the unavailable message`() = runTest {
        coEvery { signInGoogle(any()) } throws IllegalStateException("boom ana@gmail.test")
        viewModel().testMvi {
            intent(LoginIntent.GoogleTokenReceived(token))
            assertState { it.isLoading }
            assertState { it.error == LoginError.GOOGLE_UNAVAILABLE }
        }
    }
}
