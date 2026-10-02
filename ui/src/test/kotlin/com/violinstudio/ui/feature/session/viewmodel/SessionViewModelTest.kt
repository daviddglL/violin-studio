package com.violinstudio.ui.feature.session.viewmodel

import com.violinstudio.domain.feature.auth.usecase.SignOutUseCase
import com.violinstudio.domain.feature.session.SessionState
import com.violinstudio.domain.feature.session.usecase.ObserveSessionStateUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import com.violinstudio.ui.commons.testing.testMvi
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class SessionViewModelTest {
    private val signOut = mockk<SignOutUseCase>(relaxed = true)

    private fun viewModel(states: Flow<SessionState>): SessionViewModel {
        val observe = mockk<ObserveSessionStateUseCase>()
        every { observe() } returns states
        return SessionViewModel(observe, signOut)
    }

    @Test
    fun `starts in Loading and exposes every state the use case emits`() = runTest {
        val states = MutableSharedFlow<SessionState>(replay = 1)
        viewModel(states).testMvi {
            states.emit(SessionState.LoggedOut)
            assertState { it.session == SessionState.LoggedOut }
            states.emit(SessionState.Unavailable)
            assertState { it.session == SessionState.Unavailable }
        }
    }

    @Test
    fun `a process restart restores a resolved state after Loading`() = runTest {
        val vm = viewModel(flow { emit(SessionState.Loading); emit(SessionState.NeedsProfile) })
        vm.testMvi { assertState { it.session == SessionState.NeedsProfile } }
    }

    @Test
    fun `a failing session flow degrades to Unavailable instead of crashing`() = runTest {
        viewModel(flow { throw IOException("boom") }).testMvi {
            assertState { it.session == SessionState.Unavailable }
        }
    }

    @Test
    fun `SignOut calls the use case once from any state`() = runTest {
        val states = MutableSharedFlow<SessionState>(replay = 1)
        viewModel(states).testMvi {
            states.emit(SessionState.EmailUnverified("a@b.c"))
            assertState { it.session is SessionState.EmailUnverified }
            intent(SessionIntent.SignOut)
            testScheduler.advanceUntilIdle()
            coVerify(exactly = 1) { signOut() }
            states.emit(SessionState.LoggedOut)
            assertState { it.session == SessionState.LoggedOut }
        }
    }
}
