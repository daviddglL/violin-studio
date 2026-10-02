package com.violinstudio.ui.feature.session.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.common.RetryBackoff
import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.usecase.ObserveSessionStateUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.MviScenario
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val signOut = mockk<SignOutUseCase>(relaxed = true)

    // The session collector loops forever by design: cancel it so runTest can finish.
    private suspend fun SessionViewModel.testSession(
        block: suspend MviScenario<SessionUiState, SessionIntent, SessionEffect>.() -> Unit
    ) {
        try {
            testMvi(block)
        } finally {
            viewModelScope.cancel()
        }
    }

    private fun viewModel(states: Flow<SessionState>): SessionViewModel {
        val observe = mockk<ObserveSessionStateUseCase>()
        every { observe() } returns states
        return SessionViewModel(observe, signOut, RetryBackoff(1_000, 8_000))
    }

    @Test
    fun `starts in Loading and exposes every state the use case emits`() = runTest {
        val states = MutableSharedFlow<SessionState>(replay = 1)
        viewModel(states).testSession {
            states.emit(SessionState.LoggedOut)
            assertState { it.session == SessionState.LoggedOut }
            states.emit(SessionState.Unavailable)
            assertState { it.session == SessionState.Unavailable }
        }
    }

    @Test
    fun `a process restart restores a resolved state after Loading`() = runTest {
        val vm = viewModel(
            flow {
                emit(SessionState.Loading)
                emit(SessionState.NeedsProfile)
            }
        )
        vm.testSession { assertState { it.session == SessionState.NeedsProfile } }
    }

    @Test
    fun `a failing session flow degrades to Unavailable instead of crashing`() = runTest {
        viewModel(flow { throw IOException("boom") }).testSession {
            assertState { it.session == SessionState.Unavailable }
        }
    }

    @Test
    fun `SignOut calls the use case once from any state`() = runTest {
        val states = MutableSharedFlow<SessionState>(replay = 1)
        viewModel(states).testSession {
            states.emit(SessionState.EmailUnverified("a@b.c"))
            assertState { it.session is SessionState.EmailUnverified }
            intent(SessionIntent.SignOut)
            testScheduler.advanceUntilIdle()
            coVerify(exactly = 1) { signOut() }
            states.emit(SessionState.LoggedOut)
            assertState { it.session == SessionState.LoggedOut }
        }
    }

    @Test
    fun `an unexpected throw degrades to Unavailable and re-subscribes until Ready`() = runTest {
        var subscriptions = 0
        val ready = SessionState.LoggedOut
        val flaky = flow {
            if (subscriptions++ == 0) throw IOException("boom")
            emit(ready)
        }
        viewModel(flaky).testSession {
            assertState { it.session == SessionState.Unavailable }
            testScheduler.advanceTimeBy(1_001)
            assertState { it.session == ready }
            assertEquals(2, subscriptions)
        }
    }

    @Test
    fun `repeated failures back off exponentially instead of spinning`() = runTest {
        var subscriptions = 0
        val failing = flow<SessionState> {
            subscriptions++
            throw IOException("boom")
        }
        viewModel(failing).testSession {
            assertState { it.session == SessionState.Unavailable }
            testScheduler.advanceTimeBy(500)
            assertEquals(1, subscriptions)
            testScheduler.advanceTimeBy(600) // 1 s elapsed: second subscription
            assertEquals(2, subscriptions)
            testScheduler.advanceTimeBy(1_000) // next wait is 2 s: still 2
            assertEquals(2, subscriptions)
            testScheduler.advanceTimeBy(1_100)
            assertEquals(3, subscriptions)
        }
    }

    @Test
    fun `a throwing sign-out leaves the view model responsive`() = runTest {
        coEvery { signOut() } throws IllegalStateException("x") andThen Unit
        val states = MutableSharedFlow<SessionState>(replay = 1)
        viewModel(states).testSession {
            states.emit(SessionState.NeedsProfile)
            assertState { it.session == SessionState.NeedsProfile }
            intent(SessionIntent.SignOut)
            testScheduler.advanceUntilIdle()
            intent(SessionIntent.SignOut)
            testScheduler.advanceUntilIdle()
            coVerify(exactly = 2) { signOut() }
        }
    }
}
