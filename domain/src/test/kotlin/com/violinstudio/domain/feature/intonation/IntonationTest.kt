package com.violinstudio.domain.feature.intonation

import com.violinstudio.domain.feature.FakeAuthRepository
import com.violinstudio.domain.feature.curriculum.model.Curriculum
import com.violinstudio.domain.feature.intonation.failure.IntonationFailure
import com.violinstudio.domain.feature.intonation.model.IntonationExercise
import com.violinstudio.domain.feature.intonation.model.IntonationExercises
import com.violinstudio.domain.feature.intonation.model.IntonationSummary
import com.violinstudio.domain.feature.intonation.model.IntonationTracker
import com.violinstudio.domain.feature.intonation.model.NoteResult
import com.violinstudio.domain.feature.intonation.model.Tendency
import com.violinstudio.domain.feature.intonation.usecase.ObserveIntonationBestUseCase
import com.violinstudio.domain.feature.intonation.usecase.SaveIntonationScoreUseCase
import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.pitch.DetectorProfile
import com.violinstudio.domain.feature.verifiedUser
import com.violinstudio.domain.testing.FakeIntonationScoreRepository
import java.io.IOException
import kotlin.math.pow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IntonationTest {
    private val ref = ReferencePitch.DEFAULT

    private fun hz(note: Note, cents: Double) = note.frequency(ref) * 2.0.pow(cents / 1200.0)

    private fun IntonationTracker.hold(cents: Double, frames: Int = IntonationTracker.HOLD_FRAMES) {
        val note = expected!!
        repeat(frames) { onPitch(hz(note, cents)) }
    }

    @Test
    fun `cada ejercicio es de una leccion del curriculo, sube y baja, y cabe en el detector de violin`() {
        val profile = DetectorProfile.of(Instrument.VIOLIN)
        IntonationExercises.all.forEach { ex ->
            assertTrue(Curriculum.contains(ex.lessonId), ex.id)
            assertEquals(ex.notes, ex.notes.reversed(), ex.id)
            ex.notes.forEach { assertTrue(it.frequency(ref) in profile.minHz..profile.maxHz, ex.id) }
        }
        assertEquals(IntonationExercises.all.size, IntonationExercises.all.map { it.id }.toSet().size)
        assertEquals("d_major_one_octave", IntonationExercises.forLesson("e2_d_major_scale")?.id)
        assertNull(IntonationExercises.forLesson("b1_posture"))
        assertThrows<IllegalArgumentException> { IntonationExercise("x", "y", listOf(Note(60), Note(60))) }
    }

    @Test
    fun `una nota cuenta al sostenerla y su desviacion es la mediana sin el ataque`() {
        val tracker = IntonationTracker(IntonationExercises.byId("a_string_pattern")!!, ref)
        val first = tracker.expected!!
        // Ataque desafinado: no entra en la mediana.
        repeat(IntonationTracker.ATTACK_FRAMES) { tracker.onPitch(hz(first, 40.0)) }
        repeat(IntonationTracker.HOLD_FRAMES - IntonationTracker.ATTACK_FRAMES) { tracker.onPitch(hz(first, 8.0)) }
        assertEquals(1, tracker.index)
        assertEquals(8.0, tracker.results.single().cents!!, 1e-6)
        assertEquals(Note(71), tracker.expected)
    }

    @Test
    fun `un silencio o una nota distinta reinicia la espera`() {
        val tracker = IntonationTracker(IntonationExercises.byId("a_string_pattern")!!, ref)
        tracker.hold(0.0, IntonationTracker.HOLD_FRAMES - 1)
        assertNull(tracker.onPitch(null))
        tracker.hold(0.0, IntonationTracker.HOLD_FRAMES - 1)
        assertNull(tracker.onPitch(hz(Note(70), 0.0)))
        assertNull(tracker.onPitch(Double.NaN))
        assertEquals(0, tracker.index)
        assertEquals(-12.0, tracker.onPitch(hz(tracker.expected!!, -12.0))!!, 1e-6)
    }

    @Test
    fun `saltar cuenta como fallada y al acabar no procesa mas`() {
        val tracker = IntonationTracker(IntonationExercises.byId("a_string_pattern")!!, ref)
        repeat(tracker.exercise.notes.size) { if (it % 2 == 0) tracker.skip() else tracker.hold(0.0) }
        assertTrue(tracker.finished)
        assertNull(tracker.onPitch(440.0))
        tracker.skip()
        assertEquals(tracker.exercise.notes.size, tracker.results.size)
        assertEquals(tracker.exercise.notes, tracker.results.map { it.note })
        assertNull(tracker.results.first().cents)
    }

    @Test
    fun `resumen - puntuacion, afinadas y tendencias por nota`() {
        // Credito: re afinada 1; fa# (50-30)/40 = 0.5 y 0.75 (media +25); si 0.8 (-18); do# 0.95 (no es tendencia);
        // re saltada 0.
        val results = listOf(
            NoteResult(Note(62), 0.0),
            NoteResult(Note(66), 30.0),
            NoteResult(Note(78), 20.0),
            NoteResult(Note(71), -18.0),
            NoteResult(Note(73), 12.0),
            NoteResult(Note(74), null)
        )
        val summary = IntonationSummary.of(results)
        assertEquals(6, summary.total)
        assertEquals(5, summary.played)
        assertEquals(1, summary.inTune)
        assertEquals(67, summary.score) // 4.0 / 6
        assertEquals(listOf(Tendency(6, 25), Tendency(11, -18)), summary.tendencies)
    }

    @Test
    fun `resumen vacio o todo afinado`() {
        assertEquals(0, IntonationSummary.of(emptyList()).score)
        val perfect = IntonationSummary.of(listOf(NoteResult(Note(69), 9.9), NoteResult(Note(71), -10.0)))
        assertEquals(100, perfect.score)
        assertTrue(perfect.tendencies.isEmpty())
    }

    @Test
    fun `mejores puntuaciones - solo con sesion y solo si mejoran`() = runTest {
        val auth = FakeAuthRepository()
        val repo = FakeIntonationScoreRepository()
        val observe = ObserveIntonationBestUseCase(auth, repo)
        val save = SaveIntonationScoreUseCase(auth, repo)
        assertEquals(emptyMap<String, Int>(), observe().first())
        assertEquals(IntonationFailure.NoSession, save("a_string_pattern", 50).exceptionOrNull())

        auth.user = verifiedUser
        assertTrue(save("a_string_pattern", 70).isSuccess)
        assertTrue(save("a_string_pattern", 60).isSuccess)
        assertTrue(save("d_major_one_octave", 150).isSuccess)
        assertEquals(mapOf("a_string_pattern" to 70, "d_major_one_octave" to 100), observe().first())
        assertEquals(IntonationFailure.UnknownExercise, save("nope", 10).exceptionOrNull())
        assertFalse(repo.calls.any { it.contains("nope") })

        repo.writeFailure = IOException("disk")
        assertEquals(IntonationFailure.Storage, save("a_string_pattern", 90).exceptionOrNull())
    }
}
