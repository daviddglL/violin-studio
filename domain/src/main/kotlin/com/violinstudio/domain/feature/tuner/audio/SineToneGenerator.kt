package com.violinstudio.domain.feature.tuner.audio

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Seno continuo: la fase se acumula en `Double` entre bloques y la ganancia sube/baja linealmente en 20 ms
 * (sin clics al empezar ni tras [fadeOut]). Lleva estado: se rellena en orden, desde un solo hilo.
 */
class SineToneGenerator(
    private val frequency: Double,
    sampleRate: Int = PcmFormat.SAMPLE_RATE,
    private val amplitude: Float = AMPLITUDE
) : PcmGenerator {
    private val phaseStep = 2 * PI * frequency / sampleRate
    private val gainStep = 1000.0 / (RAMP_MS * sampleRate)
    private var phase = 0.0
    private var gain = 0.0

    @Volatile
    private var goal = 1.0

    /** Pide bajar a silencio; el siguiente bloque ya lleva la rampa. */
    fun fadeOut() {
        goal = 0.0
    }

    override fun fill(buffer: FloatArray, startSample: Long) {
        val target = goal
        for (i in buffer.indices) {
            gain = if (gain < target) min(target, gain + gainStep) else max(target, gain - gainStep)
            buffer[i] = (amplitude * gain * sin(phase)).toFloat()
            phase += phaseStep
            if (phase >= 2 * PI) phase -= 2 * PI
        }
    }

    companion object {
        const val AMPLITUDE = 0.5f
        const val RAMP_MS = 20
    }
}
