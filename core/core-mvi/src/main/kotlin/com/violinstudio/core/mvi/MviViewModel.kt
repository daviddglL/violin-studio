package com.violinstudio.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base MVI. Garantías:
 * - Los intents se procesan de uno en uno y en orden de llegada. Un trabajo largo que no deba
 *   bloquear intents posteriores tiene que lanzar su propia coroutine desde [handleIntent].
 * - [setState] es atómico.
 * - Cada efecto se entrega exactamente una vez; si no hay colector se guarda hasta que lo haya.
 * - Una excepción lanzada desde [handleIntent] (incluida una CancellationException espuria)
 *   termina el bucle de intents para siempre; las implementaciones deben convertir sus propios
 *   fallos en estado (como hace HomeViewModel con Result) en lugar de dejarlos propagarse.
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    private val intents = Channel<I>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (intent in intents) handleIntent(intent)
        }
    }

    fun onIntent(intent: I) {
        intents.trySend(intent)
    }

    protected abstract suspend fun handleIntent(intent: I)

    protected fun setState(reduce: S.() -> S) {
        _state.update(reduce)
    }

    protected fun sendEffect(effect: E) {
        _effects.trySend(effect)
    }
}
