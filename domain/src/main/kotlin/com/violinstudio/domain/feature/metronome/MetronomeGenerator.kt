package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.metronome.model.Tempo
import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import com.violinstudio.domain.feature.tuner.audio.PcmGenerator
import java.util.concurrent.atomic.AtomicReference

/**
 * Genera el metronomo bloque a bloque: mezcla los clics que tocan el bloque, incluidos los partidos entre bloques.
 *
 * [fill] y [finish] se llaman siempre desde el hilo de audio; [requestTempo] puede llamarse desde cualquier hilo.
 * El cambio se aplica al inicio del siguiente [fill], con su `startSample` real (aun sin escribir): el siguiente
 * tiempo conserva su posicion y los posteriores usan el nuevo intervalo; el clic en curso se termina con el
 * planificador anterior.
 */
class MetronomeGenerator(initial: BeatScheduler) : PcmGenerator {
    /** Tiempo anterior al cambio de tempo: solo se mezclan sus clics iniciados antes de [cutoff], hasta [until]. */
    private class Tail(val scheduler: BeatScheduler, val cutoff: Long, val until: Long)

    private val pending = AtomicReference<Tempo?>(null)
    private var tails: List<Tail> = emptyList()
    private var truncated = false

    /** Planificador vigente; legible desde otros hilos (p. ej. para mapear la posicion reproducida a un tiempo). */
    @Volatile
    var scheduler: BeatScheduler = initial
        private set

    /** Pide cambiar el tempo; la ultima peticion antes del siguiente bloque es la que se aplica. */
    fun requestTempo(tempo: Tempo) {
        pending.set(tempo)
    }

    override fun fill(buffer: FloatArray, startSample: Long) {
        pending.getAndSet(null)?.let { swap(it, startSample) }
        buffer.fill(0f)
        mixAll(buffer, startSample, startedBefore = Long.MAX_VALUE)
    }

    /**
     * Cierra con la cola de los clics en curso (sin clics nuevos) para no cortarlos en seco; si un clic sigue mas
     * alla del bloque, el final lleva una rampa lineal a cero.
     */
    override fun finish(buffer: FloatArray, startSample: Long): Boolean {
        buffer.fill(0f)
        val wrote = mixAll(buffer, startSample, startedBefore = startSample)
        if (truncated) {
            val ramp = minOf(RAMP_SAMPLES, buffer.size)
            for (i in 0 until ramp) buffer[buffer.size - ramp + i] *= (ramp - 1 - i).toFloat() / ramp
        }
        return wrote
    }

    private fun swap(tempo: Tempo, startSample: Long) {
        val current = scheduler
        tails = tails.filter { it.until > startSample } + Tail(current, startSample, startSample + CLICK_SIZE)
        scheduler = current.retempo(tempo, startSample)
    }

    private fun mixAll(buffer: FloatArray, start: Long, startedBefore: Long): Boolean {
        truncated = false
        var wrote = mix(scheduler, buffer, start, startedBefore)
        for (tail in tails) wrote = mix(tail.scheduler, buffer, start, minOf(startedBefore, tail.cutoff)) || wrote
        return wrote
    }

    private fun mix(scheduler: BeatScheduler, buffer: FloatArray, start: Long, startedBefore: Long): Boolean {
        val end = start + buffer.size
        var beat = scheduler.firstBeatAtOrAfter(start - CLICK_SIZE + 1)
        var wrote = false
        while (true) {
            val at = scheduler.sampleOf(beat)
            if (at >= end || at >= startedBefore) break
            val click = if (scheduler.isAccent(beat)) ClickSynth.accent else ClickSynth.normal
            val from = maxOf(at, start)
            val to = minOf(at + click.size, end)
            for (s in from until to) buffer[(s - start).toInt()] += click[(s - at).toInt()]
            wrote = wrote || to > from
            truncated = truncated || (to > from && at + click.size > end)
            beat++
        }
        return wrote
    }

    private companion object {
        val CLICK_SIZE = ClickSynth.accent.size
        const val RAMP_SAMPLES = PcmFormat.SAMPLE_RATE * 2 / 1000
    }
}
