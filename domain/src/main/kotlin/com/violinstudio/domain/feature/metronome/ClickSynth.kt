package com.violinstudio.domain.feature.metronome

import com.violinstudio.domain.feature.tuner.audio.PcmFormat
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Clics del metronomo: rafaga senoidal de 30 ms con envolvente exponencial (tau 8 ms) y rampa final a cero. */
object ClickSynth {
    private const val DURATION_MS = 30
    private const val TAU_SECONDS = 0.008
    private const val RAMP_MS = 2

    /** Solo lectura: son internos y compartidos; no modificar. */
    internal val accent: FloatArray = synth(hz = 1500.0, amplitude = 0.9)
    internal val normal: FloatArray = synth(hz = 1000.0, amplitude = 0.6)

    private fun synth(hz: Double, amplitude: Double): FloatArray {
        val rate = PcmFormat.SAMPLE_RATE
        val size = rate * DURATION_MS / 1000
        val ramp = rate * RAMP_MS / 1000
        return FloatArray(size) { i ->
            val fade = if (i >= size - ramp) (size - 1 - i).toDouble() / ramp else 1.0
            val value = amplitude * sin(2 * PI * hz * i / rate) * exp(-i / (TAU_SECONDS * rate)) * fade
            value.toFloat() + 0f // + 0f normaliza -0.0 a 0.0
        }
    }
}
