package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.SineToneGenerator
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Tono de referencia de [note] segun [ref]: suena mientras se colecta y emite `Unit` cuando arranca. Al cancelar
 * baja el volumen con la rampa del generador y despues cancela la salida (que se libera una vez); la salida se
 * lanza fuera de la cancelacion del colector justo para poder esperar a esa rampa.
 */
class PlayReferenceToneUseCase(private val output: AudioOutput) {
    operator fun invoke(note: Note, ref: ReferencePitch): Flow<Unit> = flow {
        val tone = SineToneGenerator(note.frequency(ref))
        coroutineScope {
            val playing = async(NonCancellable) { output.play(tone).collect { } }
            try {
                emit(Unit)
                playing.await()
            } finally {
                withContext(NonCancellable) {
                    if (playing.isActive) {
                        tone.fadeOut()
                        delay(FADE_OUT_WAIT_MS)
                        playing.cancelAndJoin()
                    }
                }
            }
        }
    }

    private companion object {
        /** Rampa (20 ms) mas el audio ya escrito en el buffer de la salida, que suena antes de la rampa. */
        const val FADE_OUT_WAIT_MS = 150L
    }
}
