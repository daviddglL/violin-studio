package com.violinstudio.ui.commons.testing

import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class TestMviTest {
    data class CounterState(val count: Int = 0) : UiState

    sealed interface CounterIntent : UiIntent {
        data object SlowIncrement : CounterIntent
        data object Notify : CounterIntent
    }

    data class CounterEffect(val message: String) : UiEffect

    class CounterViewModel : MviViewModel<CounterState, CounterIntent, CounterEffect>(CounterState()) {
        override suspend fun handleIntent(intent: CounterIntent) = when (intent) {
            CounterIntent.SlowIncrement -> {
                delay(100)
                setState { copy(count = count + 1) }
            }
            CounterIntent.Notify -> sendEffect(CounterEffect("hola"))
        }
    }

    @Test
    fun `assertState recibe los estados posteriores al inicial`() = runTest {
        CounterViewModel().testMvi {
            intent(CounterIntent.SlowIncrement)
            assertState { it.count == 1 }
            assertNoEffects()
        }
    }

    @Test
    fun `assertEffect recibe el efecto emitido`() = runTest {
        CounterViewModel().testMvi {
            intent(CounterIntent.Notify)
            assertEffect(CounterEffect("hola"))
        }
    }

    @Test
    fun `assertState falla con AssertionError si el predicado no se cumple`() = runTest {
        val error = runCatching {
            CounterViewModel().testMvi {
                intent(CounterIntent.SlowIncrement)
                assertState { it.count == 99 }
            }
        }.exceptionOrNull()
        assertTrue(error is AssertionError, "se esperaba AssertionError y llegó $error")
    }

    @Test
    fun `testMvi falla si quedan efectos sin comprobar`() = runTest {
        val error = runCatching {
            CounterViewModel().testMvi {
                intent(CounterIntent.Notify)
                intent(CounterIntent.SlowIncrement)
                assertState { it.count == 1 }
            }
        }.exceptionOrNull()
        assertTrue(error is AssertionError, "se esperaba AssertionError y llegó $error")
    }
}
