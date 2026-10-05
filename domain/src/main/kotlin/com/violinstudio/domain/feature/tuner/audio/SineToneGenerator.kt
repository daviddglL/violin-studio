package com.violinstudio.domain.feature.tuner.audio

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Seno continuo: la fase se acumula en `Double` entre bloques y la ganancia sube/baja linealmente en 20 ms
 * (sin clics al empezar ni en el bloque de cierre de [finish]). Lleva estado: se rellena en orden, desde un solo hilo.
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
    private var goal = 1.0

    override fun fill(buffer: FloatArray, startSample: Long) {
        for (i in buffer.indices) {
            gain = if (gain < goal) min(goal, gain + gainStep) else max(goal, gain - gainStep)
            buffer[i] = (amplitude * gain * sin(phase)).toFloat()
            phase += phaseStep
            if (phase >= 2 * PI) phase -= 2 * PI
        }
    }

    /** Un bloque (>= 20 ms) con la rampa de bajada: queda en silencio y sin saltos. */
    override fun finish(buffer: FloatArray, startSample: Long): Boolean {
        goal = 0.0
        fill(buffer, startSample)
        return true
    }

    companion object {
        const val AMPLITUDE = 0.5f
        const val RAMP_MS = 20
    }
}
