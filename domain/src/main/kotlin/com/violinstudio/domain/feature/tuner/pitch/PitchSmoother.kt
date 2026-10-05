package com.violinstudio.domain.feature.tuner.pitch

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Suaviza estimaciones de tono (una por ventana): mediana de [WINDOW] lecturas en cents absolutos,
 * histeresis de octava (un salto de 1200 +- 50 cents solo se acepta si persiste [JUMP_FRAMES] frames)
 * y espera: durante [holdFrames] ventanas consecutivas sin tono repite el ultimo tono; la siguiente devuelve `null`. No es thread-safe.
 */
class PitchSmoother(private val holdFrames: Int = DEFAULT_HOLD_FRAMES) {
    private val history = ArrayDeque<Double>(WINDOW)
    private val jump = ArrayList<Double>(JUMP_FRAMES)
    private var misses = 0
    private var last: PitchEstimate? = null

    init {
        require(holdFrames >= 1) { "holdFrames must be >= 1" }
    }

    /** Devuelve el tono suavizado, o `null` (NoPitch) cuando se agota el hold. */
    fun update(estimate: PitchEstimate?): PitchEstimate? {
        if (estimate == null) return onSilence()
        misses = 0
        val cents = 1200 * log2(estimate.frequency / REFERENCE_HZ)
        val stable = last?.let { 1200 * log2(it.frequency / REFERENCE_HZ) }
        if (stable != null && isOctaveJump(cents - stable)) {
            if (jump.isNotEmpty() && abs(cents - jump[0]) > OCTAVE_TOLERANCE) jump.clear()
            jump += cents
            if (jump.size < JUMP_FRAMES) return last
            history.clear()
            history.addAll(jump)
        } else {
            history.addLast(cents)
            if (history.size > WINDOW) history.removeFirst()
        }
        jump.clear()
        return PitchEstimate(REFERENCE_HZ * 2.0.pow(median() / 1200), estimate.confidence).also { last = it }
    }

    private fun onSilence(): PitchEstimate? {
        jump.clear()
        if (++misses <= holdFrames) return last
        history.clear()
        last = null
        return null
    }

    private fun isOctaveJump(delta: Double): Boolean {
        val octaves = (abs(delta) / OCTAVE_CENTS).roundToInt()
        return octaves >= 1 && abs(abs(delta) - octaves * OCTAVE_CENTS) <= OCTAVE_TOLERANCE
    }

    private fun median(): Double {
        val sorted = history.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    companion object {
        const val WINDOW = 5
        const val JUMP_FRAMES = 3
        const val DEFAULT_HOLD_FRAMES = 3
        private const val REFERENCE_HZ = 440.0
        private const val OCTAVE_CENTS = 1200.0
        private const val OCTAVE_TOLERANCE = 50.0
    }
}
