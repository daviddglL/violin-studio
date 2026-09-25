package com.violinstudio.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Colecta efectos MVI solo con la pantalla visible; los emitidos mientras tanto esperan en el buffer. */
@Composable
fun <E> ObserveAsEvents(events: Flow<E>, onEvent: suspend (E) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Dispatchers.Main.immediate: si repeatOnLifecycle cancela la colección justo
            // cuando llega un efecto, este withContext evita que se pierda por la carrera
            // entre la cancelación y la entrega a onEvent.
            withContext(Dispatchers.Main.immediate) {
                events.collect { onEvent(it) }
            }
        }
    }
}
