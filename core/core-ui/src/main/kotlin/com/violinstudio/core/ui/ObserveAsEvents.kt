package com.violinstudio.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Colecta efectos MVI solo con la pantalla visible; los emitidos mientras tanto esperan en el buffer.
 * Un efecto ya recibido se procesa hasta el final aunque la pantalla se detenga (p. ej. un
 * showSnackbar en curso al pasar a segundo plano); solo salir de la composición lo cancela.
 * Los efectos se procesan de uno en uno y en orden.
 */
@Composable
fun <E> ObserveAsEvents(events: Flow<E>, onEvent: suspend (E) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(events, lifecycleOwner) {
        val received = Channel<E>(Channel.UNLIMITED)
        launch { for (event in received) onEvent(event) }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Dispatchers.Main.immediate: si repeatOnLifecycle cancela la colección justo
            // cuando llega un efecto, este withContext evita que se pierda por la carrera
            // entre la cancelación y el paso a `received`.
            withContext(Dispatchers.Main.immediate) {
                events.collect { received.send(it) }
            }
        }
    }
}
