package com.violinstudio.domain.feature.metronome.usecase

import com.violinstudio.domain.feature.metronome.BeatScheduler
import com.violinstudio.domain.feature.metronome.MetronomeGenerator
import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Metronomo sobre la [AudioOutput] compartida. [invoke] crea una [MetronomeSession] (sin sonar aun): el metronomo
 * suena mientras se colecta [MetronomeSession.ticks] y para al cancelar. Cambiar el compas = nueva sesion.
 * La exclusion con el tono de referencia la hace quien llama: cancelar y esperar (acotado) el otro `play`.
 */
class RunMetronomeUseCase(private val output: AudioOutput) {
    operator fun invoke(tempo: Tempo, signature: TimeSignature): MetronomeSession =
        MetronomeSession(output, MetronomeGenerator(BeatScheduler(tempo, signature)))
}

class MetronomeSession internal constructor(
    private val output: AudioOutput,
    private val generator: MetronomeGenerator
) {
    /** Ultimo tempo pedido o vigente. */
    @Volatile
    var tempo: Tempo = generator.scheduler.tempo
        private set

    /** Cambia el tempo en vivo, desde cualquier hilo: el generador lo aplica en el siguiente bloque, sin reiniciar. */
    fun setTempo(tempo: Tempo) {
        this.tempo = tempo
        generator.requestTempo(tempo)
    }

    /**
     * Suena mientras se colecta. Emite el tiempo que suena segun los frames REPRODUCIDOS (no los escritos), sin
     * repetir. Los fallos de la salida llegan como `TunerFailure.AudioOutputUnavailable`.
     */
    val ticks: Flow<BeatTick> = output.play(generator)
        .map { played -> tickAt(played) }
        .filterNotNull()
        .distinctUntilChanged()

    /** [played] frames ya sonados: el ultimo con sonido es el indice `played - 1`. */
    private fun tickAt(played: Long): BeatTick? {
        val scheduler = generator.scheduler
        val beat = scheduler.beatAt(played - 1)
        if (beat < 0) return null
        return BeatTick(beat, (beat % scheduler.signature.beats).toInt(), scheduler.isAccent(beat))
    }
}
