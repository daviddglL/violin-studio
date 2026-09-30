package com.violinstudio.ui.commons.mvi

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    data class TestState(val log: String = "", val count: Int = 0) : UiState

    sealed interface TestIntent : UiIntent {
        data class Append(val value: String, val delayMs: Long = 0) : TestIntent
        data object Increment : TestIntent
        data class Emit(val value: String) : TestIntent
    }

    data class TestEffect(val value: String) : UiEffect

    class TestViewModel : MviViewModel<TestState, TestIntent, TestEffect>(TestState()) {
        override suspend fun handleIntent(intent: TestIntent) = when (intent) {
            is TestIntent.Append -> {
                delay(intent.delayMs)
                setState { copy(log = log + intent.value) }
            }
            TestIntent.Increment -> setState { copy(count = count + 1) }
            is TestIntent.Emit -> sendEffect(TestEffect(intent.value))
        }
    }

    @Test
    fun `expone el estado inicial`() = runTest(dispatcher) {
        assertEquals(TestState(), TestViewModel().state.value)
    }

    @Test
    fun `procesa los intents en orden de llegada aunque el primero tarde`() = runTest(dispatcher) {
        val vm = TestViewModel()
        vm.onIntent(TestIntent.Append("a", delayMs = 1_000))
        vm.onIntent(TestIntent.Append("b"))
        advanceUntilIdle()
        assertEquals("ab", vm.state.value.log)
    }

    @Test
    fun `mil intents enviados desde varios hilos no pierden actualizaciones`() = runTest(dispatcher) {
        val vm = TestViewModel()
        withContext(Dispatchers.Default) {
            coroutineScope { repeat(1_000) { launch { vm.onIntent(TestIntent.Increment) } } }
        }
        advanceUntilIdle()
        assertEquals(1_000, vm.state.value.count)
    }

    @Test
    fun `un efecto emitido sin colector se entrega una sola vez al volver a colectar`() = runTest(dispatcher) {
        val vm = TestViewModel()
        vm.onIntent(TestIntent.Emit("error"))
        advanceUntilIdle() // no hay colector: simula la pantalla rotando

        vm.effects.test {
            assertEquals(TestEffect("error"), awaitItem())
            expectNoEvents()
        }
        vm.effects.test { expectNoEvents() } // un segundo colector no lo recibe otra vez
    }
}
