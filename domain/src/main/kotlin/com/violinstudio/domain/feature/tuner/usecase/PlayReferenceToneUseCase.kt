package com.violinstudio.domain.feature.tuner.usecase

import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import com.violinstudio.domain.feature.tuner.audio.SineToneGenerator
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Tono de referencia de [note] segun [ref]: suena mientras se colecta y emite `Unit` UNA vez, cuando la salida ya
 * esta sonando. La parada sin clic (rampa de salida) y la liberacion las gestiona la [AudioOutput] al cancelar.
 */
class PlayReferenceToneUseCase(private val output: AudioOutput) {
    operator fun invoke(note: Note, ref: ReferencePitch): Flow<Unit> =
        output.play(SineToneGenerator(note.frequency(ref))).map { }.distinctUntilChanged()
}
