package com.violinstudio.ui.feature.consent.viewmodel

import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.consent.failure.ConsentFailure
import com.violinstudio.domain.feature.consent.model.IdentityConfig
import com.violinstudio.domain.feature.consent.usecase.AcceptPolicyUseCase
import com.violinstudio.domain.feature.consent.usecase.GetIdentityConfigUseCase
import com.violinstudio.domain.feature.session.ConsentReason
import com.violinstudio.domain.feature.session.SessionRefreshTrigger
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
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
class ConsentViewModelTest {
    private val accept = mockk<AcceptPolicyUseCase>()
    private val getConfig = mockk<GetIdentityConfigUseCase>()
    private val signOut = mockk<SignOutUseCase>(relaxed = true)
    private val trigger = mockk<SessionRefreshTrigger>(relaxed = true)
    private val v1 = IdentityConfig(1, "https://example.test/policy/1", 14, true)
    private val v2 = IdentityConfig(2, "https://example.test/policy/2", 14, true)

    private fun viewModel() = ConsentViewModel(accept, getConfig, signOut, trigger)

    private fun acceptAnswers(result: Result<Unit>) {
        coEvery { accept(any()) } coAnswers {
            delay(100)
            result
        }
    }

    private suspend fun Scenario.load(config: IdentityConfig = v1, reason: ConsentReason = ConsentReason.FIRST) {
        intent(ConsentIntent.SessionUpdated(config, reason))
        assertState { it.config == config && it.reason == reason }
    }

    private suspend fun Scenario.check() {
        intent(ConsentIntent.CheckedChanged(true))
        assertState { it.checked }
    }

    @Test
    fun `accept sends the policy version of the session state and success blocks a second accept`() = runTest {
        acceptAnswers(Result.success(Unit))
        viewModel().testMvi {
            load(v2)
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.succeeded && !it.canAccept && it.error == null }
            assertNoEffects()
        }
        coVerify(exactly = 1) { accept(2) }
    }

    @Test
    fun `accept without the checkbox never reaches the use case`() = runTest {
        viewModel().testMvi {
            load()
            intent(ConsentIntent.Accept)
            intent(ConsentIntent.CheckedChanged(true))
            assertState { it.checked && !it.isLoading }
        }
        coVerify(exactly = 0) { accept(any()) }
    }

    @Test
    fun `a double accept is dropped`() = runTest {
        acceptAnswers(Result.success(Unit))
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        coVerify(exactly = 1) { accept(any()) }
    }

    @Test
    fun `an outdated policy reloads the config and asks to accept the new version`() = runTest {
        acceptAnswers(Result.failure(ConsentFailure.PolicyOutdated(2)))
        coEvery { getConfig() } returns Result.success(v2)
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState {
                it.config == v2 && !it.checked && !it.isLoading && it.error == ConsentError.POLICY_CHANGED
            }
            acceptAnswers(Result.success(Unit))
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading && it.error == null }
            assertState { it.succeeded }
        }
        coVerify(exactly = 1) { accept(1) }
        coVerify(exactly = 1) { accept(2) }
    }

    @Test
    fun `an outdated policy whose reload fails keeps the user on the screen with a message`() = runTest {
        acceptAnswers(Result.failure(ConsentFailure.PolicyOutdated(null)))
        coEvery { getConfig() } returns Result.failure(ConsentFailure.Network)
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.error == ConsentError.POLICY_UNAVAILABLE && !it.succeeded && !it.isLoading }
        }
    }

    @Test
    fun `a claims refresh failure shows a retry message, does not advance and a retry calls the use case again`() =
        runTest {
            // AcceptPolicyUseCase ya devuelve Network/Unknown cuando el refresco de claims no confirma consentOk.
            acceptAnswers(Result.failure(ConsentFailure.Network))
            viewModel().testMvi {
                load()
                check()
                intent(ConsentIntent.Accept)
                assertState { it.isLoading }
                assertState { it.error == ConsentError.NETWORK && !it.succeeded && it.canAccept }
                acceptAnswers(Result.success(Unit))
                intent(ConsentIntent.Accept)
                assertState { it.isLoading && it.error == null }
                assertState { it.succeeded }
            }
            coVerify(exactly = 2) { accept(1) }
        }

    @Test
    fun `other failures map to their message and never advance`() = runTest {
        val cases = listOf(
            ConsentFailure.GuardianRequired to ConsentError.GUARDIAN_REQUIRED,
            ConsentFailure.UnderageNotAllowed to ConsentError.GUARDIAN_REQUIRED,
            ConsentFailure.Unknown() to ConsentError.UNKNOWN,
            ConsentFailure.InvalidArgument("policyVersion") to ConsentError.UNKNOWN,
            ConsentFailure.EmailNotVerified to ConsentError.UNKNOWN,
            ConsentFailure.NoProfile to ConsentError.UNKNOWN
        )
        for ((failure, error) in cases) {
            acceptAnswers(Result.failure(failure))
            viewModel().testMvi {
                load()
                check()
                intent(ConsentIntent.Accept)
                assertState { it.isLoading }
                assertState { it.error == error && !it.succeeded }
            }
        }
    }

    @Test
    fun `an already granted answer counts as success because the session follows the profile`() = runTest {
        acceptAnswers(Result.failure(ConsentFailure.AlreadyGranted))
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.succeeded && it.error == null }
        }
    }

    @Test
    fun `an unexpected exception from the use case becomes a message and the loop survives`() = runTest {
        coEvery { accept(any()) } throws IllegalStateException("boom")
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.error == ConsentError.UNKNOWN && !it.isLoading }
            intent(ConsentIntent.CheckedChanged(false))
            assertState { !it.checked }
        }
    }

    @Test
    fun `open policy emits the server url only when it is https`() = runTest {
        viewModel().testMvi {
            load()
            intent(ConsentIntent.OpenPolicy)
            assertEffect(ConsentEffect.OpenPolicy("https://example.test/policy/1"))
            val insecure = IdentityConfig(3, "http://example.test/policy", 14, true)
            intent(ConsentIntent.SessionUpdated(insecure, ConsentReason.FIRST))
            assertState { it.policyVersion == 3 }
            intent(ConsentIntent.OpenPolicy)
            assertState { it.policyLinkFailed }
            assertNoEffects()
        }
    }

    @Test
    fun `open policy before the policy is loaded does nothing`() = runTest {
        viewModel().testMvi {
            intent(ConsentIntent.OpenPolicy)
            intent(ConsentIntent.CheckedChanged(true))
            assertState { it.checked && !it.policyLinkFailed }
            assertNoEffects()
        }
    }

    @Test
    fun `a failed link open from the route is flagged`() = runTest {
        viewModel().testMvi {
            load()
            intent(ConsentIntent.PolicyLinkFailed)
            assertState { it.policyLinkFailed }
        }
    }

    @Test
    fun `a hot bump from the session resets the checkbox and shows the new policy`() = runTest {
        viewModel().testMvi {
            load(v1)
            check()
            intent(ConsentIntent.SessionUpdated(v2, ConsentReason.POLICY_UPDATED))
            assertState { it.config == v2 && !it.checked && it.reason == ConsentReason.POLICY_UPDATED }
        }
    }

    @Test
    fun `an outdated policy asks the session to refresh so it resolves against the new version`() = runTest {
        acceptAnswers(Result.failure(ConsentFailure.PolicyOutdated(2)))
        coEvery { getConfig() } returns Result.success(v2)
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.config == v2 && !it.isLoading }
        }
        verify(exactly = 1) { trigger.requestRefresh() }
    }

    @Test
    fun `a successful accept asks the session to refresh`() = runTest {
        acceptAnswers(Result.success(Unit))
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        verify(exactly = 1) { trigger.requestRefresh() }
    }

    @Test
    fun `an already granted answer asks the session to refresh`() = runTest {
        acceptAnswers(Result.failure(ConsentFailure.AlreadyGranted))
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.succeeded }
        }
        verify(exactly = 1) { trigger.requestRefresh() }
    }

    @Test
    fun `failures that keep the user on the screen do not ask for a refresh`() = runTest {
        acceptAnswers(Result.failure(ConsentFailure.Network))
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.Accept)
            assertState { it.isLoading }
            assertState { it.error == ConsentError.NETWORK }
        }
        verify(exactly = 0) { trigger.requestRefresh() }
    }

    @Test
    fun `sign out calls the use case and survives its failure`() = runTest {
        coEvery { signOut() } throws IllegalStateException("boom")
        viewModel().testMvi {
            intent(ConsentIntent.SignOut)
            intent(ConsentIntent.CheckedChanged(true))
            assertState { it.checked }
        }
        coVerify(exactly = 1) { signOut() }
    }

    @Test
    fun `while the shared delete flow is active accept and the checkbox are dropped`() = runTest {
        acceptAnswers(Result.success(Unit))
        viewModel().testMvi {
            load()
            check()
            intent(ConsentIntent.DeleteActiveChanged(true))
            assertState { it.deleteActive && !it.canAccept }
            intent(ConsentIntent.Accept)
            intent(ConsentIntent.CheckedChanged(false))
            intent(ConsentIntent.DeleteActiveChanged(false))
            assertState { !it.deleteActive && it.canAccept && it.checked && !it.isLoading }
        }
        coVerify(exactly = 0) { accept(any()) }
    }
}

private typealias Scenario = MviScenario<ConsentState, ConsentIntent, ConsentEffect>
