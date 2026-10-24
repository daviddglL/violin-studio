package com.violinstudio.domain.feature.intonation.model

import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import kotlin.math.abs
import kotlin.math.log2

/** Nota del ejercicio y su desviacion en cents (mediana de lo sostenido); `null` si se salto. */
data class NoteResult(val note: Note, val cents: Double?)

/**
 * Sigue un ejercicio lectura a lectura (una por ventana del detector, ~23 ms en violin). Una nota cuenta cuando se
 * sostiene [HOLD_FRAMES] lecturas seguidas a menos de [MATCH_CENTS] de la esperada; su desviacion es la mediana sin
 * las [ATTACK_FRAMES] primeras (el ataque). Un silencio o una nota distinta reinicia la espera. No es thread-safe.
 */
class IntonationTracker(val exercise: IntonationExercise, private val ref: ReferencePitch = ReferencePitch.DEFAULT) {
    private val done = mutableListOf<NoteResult>()
    private val run = mutableListOf<Double>()

    val results: List<NoteResult> get() = done.toList()
    val index: Int get() = done.size
    val expected: Note? get() = exercise.notes.getOrNull(done.size)
    val finished: Boolean get() = expected == null

    /**
     * Procesa una lectura ([frequency] `null` = sin tono). Devuelve los cents respecto a la nota esperada si la
     * lectura es esa nota, o `null` si no lo es (o ya termino).
     */
    fun onPitch(frequency: Double?): Double? {
        val target = expected ?: return null
        val cents = frequency?.takeIf { it.isFinite() && it > 0.0 }?.let { centsFrom(it, target) }
        if (cents == null || abs(cents) >= MATCH_CENTS) {
            run.clear()
            return null
        }
        run += cents
        if (run.size >= HOLD_FRAMES) {
            done += NoteResult(target, median(run.drop(ATTACK_FRAMES)))
            run.clear()
        }
        return cents
    }

    /** Salta la nota esperada (cuenta como fallada). */
    fun skip() {
        val target = expected ?: return
        done += NoteResult(target, null)
        run.clear()
    }

    private fun centsFrom(frequency: Double, note: Note) = 1200.0 * log2(frequency / note.frequency(ref))

    companion object {
        /** ~280 ms sostenida. */
        const val HOLD_FRAMES = 12
        const val ATTACK_FRAMES = 3

        /** Medio semitono: mas lejos ya es otra nota. */
        const val MATCH_CENTS = 50.0

        internal fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
