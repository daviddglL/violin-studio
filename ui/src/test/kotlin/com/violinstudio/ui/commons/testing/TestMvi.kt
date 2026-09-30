package com.violinstudio.ui.commons.testing

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.violinstudio.ui.commons.mvi.MviViewModel
import com.violinstudio.ui.commons.mvi.UiEffect
import com.violinstudio.ui.commons.mvi.UiIntent
import com.violinstudio.ui.commons.mvi.UiState

class MviScenario<S : UiState, I : UiIntent, E : UiEffect> internal constructor(
    private val viewModel: MviViewModel<S, I, E>,
    private val states: ReceiveTurbine<S>,
    private val effects: ReceiveTurbine<E>
) {
    fun intent(intent: I) = viewModel.onIntent(intent)

    /** Espera el siguiente estado (StateFlow descarta estados intermedios iguales o no observados). */
    suspend fun assertState(predicate: (S) -> Boolean) {
        val state = states.awaitItem()
        if (!predicate(state)) throw AssertionError("Estado inesperado: $state")
    }

    suspend fun assertEffect(expected: E) {
        val effect = effects.awaitItem()
        if (effect != expected) throw AssertionError("Efecto esperado $expected, llegó $effect")
    }

    fun assertNoEffects() = effects.expectNoEvents()
}

/**
 * Colecta estado y efectos del ViewModel mientras se ejecuta [block].
 * El estado inicial se consume antes de [block]. Al terminar falla si quedan efectos sin comprobar.
 */
suspend fun <S : UiState, I : UiIntent, E : UiEffect> MviViewModel<S, I, E>.testMvi(
    block: suspend MviScenario<S, I, E>.() -> Unit
) = turbineScope {
    val stateTurbine = this@testMvi.state.testIn(this, name = "state")
    val effectTurbine = this@testMvi.effects.testIn(this, name = "effects")
    stateTurbine.awaitItem()
    MviScenario(this@testMvi, stateTurbine, effectTurbine).block()
    stateTurbine.cancelAndIgnoreRemainingEvents()
    effectTurbine.ensureAllEventsConsumed()
    effectTurbine.cancel()
}
