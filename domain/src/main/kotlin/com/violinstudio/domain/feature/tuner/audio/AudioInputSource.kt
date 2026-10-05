package com.violinstudio.domain.feature.tuner.audio

import kotlinx.coroutines.flow.Flow

/** Fuente de audio en memoria: muestras normalizadas (-1..1), sin persistencia. */
interface AudioInputSource {
    /**
     * Chunks de [chunkSize] muestras mono a 44 100 Hz hasta cancelar la coleccion.
     * Los fallos llegan como [com.violinstudio.domain.feature.tuner.failure.TunerFailure].
     */
    fun frames(chunkSize: Int): Flow<FloatArray>
}
