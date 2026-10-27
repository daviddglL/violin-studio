package com.violinstudio.ui.feature.reminder.viewmodel

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import com.violinstudio.domain.feature.practice.model.PracticeReminder
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeReminderUseCase
import com.violinstudio.domain.feature.practice.usecase.SetPracticeReminderUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderViewModelTest {
    private val saved = MutableStateFlow(PracticeReminder())
    private val observe = mockk<ObservePracticeReminderUseCase> { every { this@mockk() } returns saved }
    private val set = mockk<SetPracticeReminderUseCase> {
        coEvery { this@mockk(any()) } answers {
            saved.value = firstArg()
            Result.success(Unit)
        }
    }

    @Test
    fun `muestra lo guardado y enciende o cambia la hora sobre ello`() = runTest {
        val vm = ReminderViewModel(observe, set)
        advanceUntilIdle()
        assertTrue(vm.state.value.loaded)
        vm.onIntent(ReminderIntent.Toggle(true))
        advanceUntilIdle()
        vm.onIntent(ReminderIntent.SetTime(LocalTime.of(20, 15)))
        advanceUntilIdle()
        assertEquals(PracticeReminder(true, LocalTime.of(20, 15)), vm.state.value.reminder)
        coVerify { set(PracticeReminder(true, PracticeReminder.DEFAULT_TIME)) }
    }

    @Test
    fun `si no se puede guardar lo avisa y sigue viendose lo anterior`() = runTest {
        coEvery { set(any()) } returns Result.failure(PracticeFailure.Unknown)
        val vm = ReminderViewModel(observe, set)
        advanceUntilIdle()
        vm.onIntent(ReminderIntent.Toggle(true))
        advanceUntilIdle()
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.reminder.enabled)
    }
}
