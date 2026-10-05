package com.violinstudio.domain.feature.metronome.usecase

import com.violinstudio.domain.feature.metronome.BeatScheduler
import com.violinstudio.domain.feature.metronome.MetronomeGenerator
import com.violinstudio.domain.feature.metronome.model.BeatTick
import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.audio.AudioOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Metronomo sobre la [AudioOutput] compartida. [invoke] crea una [MetronomeSession] (sin sonar aun): el metronomo
 * suena mientras se colecta [MetronomeSession.ticks] y para al cancelar. Cambiar el compas = nueva sesion.
 * La exclusion con el tono de referencia la hace quien llama: cancelar el otro `play` y esperarlo con un limite
 * (`withTimeoutOrNull(500)`); el Mutex de la salida acota cualquier solape a ~300 ms.
 */
class RunMetronomeUseCase(private val output: AudioOutput) {
    operator fun invoke(tempo: Tempo, signature: TimeSignature): MetronomeSession =
        MetronomeSession(output, tempo, signature)
}

class MetronomeSession internal constructor(
    private val output: AudioOutput,
    initialTempo: Tempo,
    private val signature: TimeSignature
) {
    private val lock = Any()
    private var live: MetronomeGenerator? = null

    /** Ultimo tempo pedido (o el inicial). Cada colecta de [ticks] arranca con este tempo. */
    @Volatile
    var tempo: Tempo = initialTempo
        private set

    /**
     * Cambia el tempo, desde cualquier hilo. Sonando: el generador lo aplica en el siguiente bloque, sin reiniciar.
     * Sin colecta en curso solo actualiza [tempo] (vale para la siguiente colecta).
     */
    fun setTempo(tempo: Tempo) {
        synchronized(lock) {
            this.tempo = tempo
            live?.requestTempo(tempo)
        }
    }

    /**
     * Suena mientras se colecta; cada colecta es una reproduccion NUEVA que empieza en el tiempo 0 (muestra 0) con
     * el [tempo] vigente. Emite el tiempo que suena segun los frames REPRODUCIDOS (no los escritos), sin repetir.
     * Tras [setTempo] el planificador cambia al escribir mientras lo reproducido va con retraso (~2 bloques): el
     * indicador puede adelantarse un tiempo durante ese retraso (~50 ms) antes de coincidir con lo que suena.
     * Los fallos de la salida llegan como `TunerFailure.AudioOutputUnavailable`.
     */
    val ticks: Flow<BeatTick> = flow {
        val generator = synchronized(lock) {
            MetronomeGenerator(BeatScheduler(tempo, signature)).also { live = it }
        }
        try {
            emitAll(output.play(generator).map { played -> tickAt(generator, played) }.filterNotNull())
        } finally {
            synchronized(lock) { if (live === generator) live = null }
        }
    }.distinctUntilChanged()

    /** [played] frames ya sonados: el ultimo con sonido es el indice `played - 1`. */
    private fun tickAt(generator: MetronomeGenerator, played: Long): BeatTick? {
        val scheduler = generator.scheduler
        val beat = scheduler.beatAt(played - 1)
        if (beat < 0) return null
        return BeatTick(beat, (beat % signature.beats).toInt(), scheduler.isAccent(beat))
    }
}
