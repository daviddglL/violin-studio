package com.violinstudio.data.commons.utils

import kotlinx.coroutines.CancellationException

/**
 * Ejecuta [block] y convierte sus excepciones en `Result.failure(mapError(e))`. La cancelación se relanza:
 * un repositorio nunca debe tragarse la cancelación de la corrutina.
 */
suspend inline fun <T> resultOf(mapError: (Exception) -> Throwable, block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(mapError(e))
}
