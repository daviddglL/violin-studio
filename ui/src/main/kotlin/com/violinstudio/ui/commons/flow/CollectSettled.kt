package com.violinstudio.ui.commons.flow

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Margen en el que una primera emisión vacía puede ser solo la caché local y no la respuesta del servidor. */
const val SETTLE_MS = 1_500L

/**
 * La primera emisión vacía espera [SETTLE_MS] antes de entregarse; otras emisiones vacías dentro del margen NO lo
 * reinician (se entrega la última al vencer). Una emisión con datos cancela la espera y se entrega al instante. Tras
 * haber visto algo, el vacío es inmediato. Al terminar el flujo se espera a la entrega pendiente.
 *
 * Supone un único hilo de despacho (p. ej. `Dispatchers.Main`): `seen`, `waiting` y `latest` no están sincronizados.
 */
suspend fun <T> Flow<T>.collectSettled(isEmpty: (T) -> Boolean, action: suspend (T) -> Unit) = coroutineScope {
    var seen = false
    var waiting: Job? = null
    var latest: T? = null
    collect { value ->
        if (isEmpty(value) && !seen) {
            latest = value
            if (waiting == null) {
                waiting = launch {
                    delay(SETTLE_MS)
                    seen = true
                    @Suppress("UNCHECKED_CAST")
                    action(latest as T)
                }
            }
        } else {
            waiting?.cancel()
            waiting = null
            seen = true
            action(value)
        }
    }
}
