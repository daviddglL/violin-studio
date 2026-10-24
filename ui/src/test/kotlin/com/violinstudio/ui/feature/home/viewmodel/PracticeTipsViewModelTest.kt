package com.violinstudio.ui.feature.home.viewmodel

import androidx.lifecycle.viewModelScope
import com.violinstudio.domain.feature.practice.model.PracticeTip
import com.violinstudio.domain.feature.practice.usecase.ObservePracticeTipsUseCase
import com.violinstudio.ui.commons.testing.MainDispatcherExtension
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeTipsViewModelTest {
    @Test
    fun `refleja los consejos y se actualiza`() = runTest {
        val tips = MutableStateFlow<List<PracticeTip>>(listOf(PracticeTip.FirstSession))
        val vm = PracticeTipsViewModel(mockk<ObservePracticeTipsUseCase> { every { this@mockk() } returns tips })
        advanceUntilIdle()
        assertEquals(listOf<PracticeTip>(PracticeTip.FirstSession), vm.state.value.tips)
        tips.value = listOf(PracticeTip.KeepStreak(4))
        advanceUntilIdle()
        assertEquals(listOf<PracticeTip>(PracticeTip.KeepStreak(4)), vm.state.value.tips)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `si el historial falla no hay consejos`() = runTest {
        val failing = flow<List<PracticeTip>> { throw IOException("offline") }
        val vm = PracticeTipsViewModel(mockk<ObservePracticeTipsUseCase> { every { this@mockk() } returns failing })
        advanceUntilIdle()
        assertEquals(emptyList<PracticeTip>(), vm.state.value.tips)
        vm.viewModelScope.cancel()
    }
}
