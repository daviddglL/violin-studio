package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.tuner.audio.PcmGenerator

/** Genera el metronomo bloque a bloque: mezcla los clics que tocan el bloque, incluidos los partidos entre bloques. */
class MetronomeGenerator(private val scheduler: BeatScheduler) : PcmGenerator {
    override fun fill(buffer: FloatArray, startSample: Long) {
        buffer.fill(0f)
        mix(buffer, startSample, startedBefore = Long.MAX_VALUE)
    }

    /** Cierra con la cola del clic en curso (sin clics nuevos) para no cortarlo en seco. */
    override fun finish(buffer: FloatArray, startSample: Long): Boolean {
        buffer.fill(0f)
        return mix(buffer, startSample, startedBefore = startSample)
    }

    private fun mix(buffer: FloatArray, start: Long, startedBefore: Long): Boolean {
        val end = start + buffer.size
        var beat = scheduler.firstBeatAtOrAfter(start - ClickSynth.accent.size + 1)
        var wrote = false
        while (true) {
            val at = scheduler.sampleOf(beat)
            if (at >= end || at >= startedBefore) break
            val click = if (scheduler.isAccent(beat)) ClickSynth.accent else ClickSynth.normal
            val from = maxOf(at, start)
            val to = minOf(at + click.size, end)
            for (s in from until to) buffer[(s - start).toInt()] += click[(s - at).toInt()]
            wrote = wrote || to > from
            beat++
        }
        return wrote
    }
}
