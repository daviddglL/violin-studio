package com.violinstudio.domain.feature.intonation.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** Nota (clase de altura 0 = do ... 11 = si) que se tiende a tocar alta (cents > 0) o baja (cents < 0). */
data class Tendency(val pitchClass: Int, val cents: Int)

/**
 * Resultado de un intento. [score] (0..100): cada nota afinada (|cents| <= [IN_TUNE_CENTS]) vale 1, se reduce en linea
 * hasta 0 a [IntonationTracker.MATCH_CENTS] y una saltada vale 0. [tendencies]: como mucho [MAX_TENDENCIES] notas
 * cuya media se desvia al menos [TENDENCY_CENTS], de peor a mejor.
 */
data class IntonationSummary(
    val total: Int,
    val played: Int,
    val inTune: Int,
    val score: Int,
    val tendencies: List<Tendency>
) {
    companion object {
        const val IN_TUNE_CENTS = 10.0
        const val TENDENCY_CENTS = 15.0
        const val MAX_TENDENCIES = 2

        fun of(results: List<NoteResult>): IntonationSummary {
            val played = results.mapNotNull { r -> r.cents?.let { r.note to it } }
            val credit = played.sumOf { (_, cents) -> credit(cents) }
            val tendencies = played
                .groupBy({ (note, _) -> Math.floorMod(note.midi, 12) }, { (_, cents) -> cents })
                .mapValues { (_, cents) -> cents.average() }
                .filterValues { abs(it) >= TENDENCY_CENTS }
                .entries
                .sortedByDescending { abs(it.value) }
                .take(MAX_TENDENCIES)
                .map { Tendency(it.key, it.value.roundToInt()) }
            return IntonationSummary(
                total = results.size,
                played = played.size,
                inTune = played.count { (_, cents) -> abs(cents) <= IN_TUNE_CENTS },
                score = if (results.isEmpty()) 0 else (100 * credit / results.size).roundToInt(),
                tendencies = tendencies
            )
        }

        private fun credit(cents: Double): Double {
            val off = abs(cents)
            if (off <= IN_TUNE_CENTS) return 1.0
            return ((IntonationTracker.MATCH_CENTS - off) / (IntonationTracker.MATCH_CENTS - IN_TUNE_CENTS))
                .coerceIn(0.0, 1.0)
        }
    }
}
