package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.metronome.model.TimeSignature
import com.violinstudio.domain.feature.tuner.audio.PcmFormat

/**
 * Posicion en muestras de cada tiempo, calculada siempre desde el indice del tiempo con aritmetica entera
 * (`origin + round((k - firstBeat) * 60 * sr / bpm)`): nunca se acumula error. Inmutable; el tiempo [firstBeat]
 * cae en [originSample]. En 6/8 el BPM cuenta corcheas (cada clic es un tiempo). Los indices de tiempo cuentan
 * desde 0 y [isAccent] marca el primero de cada compas.
 */
class BeatScheduler(
    val tempo: Tempo,
    val signature: TimeSignature,
    val originSample: Long = 0,
    val firstBeat: Long = 0
) {
    private val sampleRate = PcmFormat.SAMPLE_RATE

    /** Muestra en la que suena el tiempo [beat] (>= [firstBeat]). */
    fun sampleOf(beat: Long): Long {
        require(beat >= firstBeat) { "beat $beat < firstBeat $firstBeat" }
        val numerator = (beat - firstBeat) * 60L * sampleRate
        return originSample + (2 * numerator + tempo.bpm) / (2L * tempo.bpm)
    }

    fun isAccent(beat: Long): Boolean = signature.isAccent(beat)

    /** Ultimo tiempo cuya muestra es <= [sample] (el que esta sonando); `firstBeat - 1` si aun no ha empezado ninguno. */
    fun beatAt(sample: Long): Long {
        if (sample < originSample) return firstBeat - 1
        var beat = firstBeat + (sample - originSample) * tempo.bpm / (60L * sampleRate)
        while (sampleOf(beat) > sample) beat--
        while (sampleOf(beat + 1) <= sample) beat++
        return beat
    }

    /** Primer tiempo cuya muestra es >= [sample] (nunca anterior a [firstBeat]). */
    fun firstBeatAtOrAfter(sample: Long): Long {
        if (sample <= originSample) return firstBeat
        val beat = beatAt(sample)
        return if (sampleOf(beat) >= sample) beat else beat + 1
    }

    /**
     * Cambia el tempo sin saltar de fase: el siguiente tiempo a partir de [atSample] conserva su posicion (es el
     * nuevo origen) y los posteriores usan el nuevo intervalo; el acento sigue la cuenta de tiempos.
     */
    fun retempo(newTempo: Tempo, atSample: Long): BeatScheduler {
        val next = firstBeatAtOrAfter(atSample)
        return BeatScheduler(newTempo, signature, sampleOf(next), next)
    }
}
