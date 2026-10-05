package com.violinstudio.domain.feature.practice.usecase

import com.violinstudio.domain.feature.practice.failure.PracticeFailure
import kotlinx.coroutines.CancellationException

/** Un fallo inesperado del almacen local (E-S) nunca debe tumbar al llamador: pasa a `Unknown`; la cancelacion no. */
internal inline fun <T> guardedStore(block: () -> Result<T>): Result<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    Result.failure(PracticeFailure.Unknown)
}
