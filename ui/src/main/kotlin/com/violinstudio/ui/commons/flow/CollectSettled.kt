package com.violinstudio.ui.commons.flow

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** Margen en el que una primera emisión vacía puede ser solo la caché local y no la respuesta del servidor. */
const val SETTLE_MS = 1_500L

/**
 * Como `collectLatest`, pero la primera emisión vacía espera [SETTLE_MS] antes de entregarse (una emisión nueva
 * cancela la espera). Tras haber visto algo, el vacío es inmediato.
 */
suspend fun <T> Flow<T>.collectSettled(isEmpty: (T) -> Boolean, action: suspend (T) -> Unit) {
    var seen = false
    collectLatest { value ->
        if (isEmpty(value) && !seen) delay(SETTLE_MS)
        seen = true
        action(value)
    }
}
