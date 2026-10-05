package com.violinstudio.ui.feature.tuner.viewmodel

import com.violinstudio.domain.feature.profile.model.Instrument
import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.model.Note
import com.violinstudio.domain.feature.tuner.model.TunerReading
import com.violinstudio.domain.feature.tuner.model.TuningTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunerReducerTest {
    private val initial = TunerState()

    private fun pitch(cents: Double) = TunerReading.Pitch(440.0, TuningTarget.OpenString(Note(69), 2), cents, 0.9)

    private fun reduce(state: TunerState, m: TunerMutation) = TunerReducer.reduce(state, m)

    @Test
    fun `permiso concedido pasa a Granted`() {
        val s = reduce(initial, TunerMutation.PermissionResolved(granted = true, rationale = false))
        assertEquals(MicState.GRANTED, s.mic)
    }

    @Test
    fun `denegado con rationale es Denied`() {
        val s = reduce(initial, TunerMutation.PermissionResolved(granted = false, rationale = true))
        assertEquals(MicState.DENIED, s.mic)
        assertFalse(s.showRationale)
    }

    @Test
    fun `denegado sin rationale es PermanentlyDenied`() {
        val s = reduce(initial, TunerMutation.PermissionResolved(granted = false, rationale = false))
        assertEquals(MicState.PERMANENTLY_DENIED, s.mic)
    }

    @Test
    fun `concedido limpia el rationale`() {
        val s = reduce(
            initial.copy(mic = MicState.PERMANENTLY_DENIED, showRationale = true),
            TunerMutation.PermissionResolved(true, false)
        )
        assertEquals(MicState.GRANTED, s.mic)
        assertFalse(s.showRationale)
    }

    @Test
    fun `decision de arranque segun permiso`() {
        val permanent = initial.copy(mic = MicState.PERMANENTLY_DENIED)
        val denied = initial.copy(mic = MicState.DENIED)
        assertEquals(StartDecision.CAPTURE, TunerReducer.startDecision(initial, true, false))
        assertEquals(StartDecision.REQUEST, TunerReducer.startDecision(initial, false, false))
        assertEquals(StartDecision.RATIONALE, TunerReducer.startDecision(initial, false, true))
        assertEquals(StartDecision.BLOCKED, TunerReducer.startDecision(permanent, false, false))
        assertEquals(StartDecision.CAPTURE, TunerReducer.startDecision(permanent, true, false))
        assertEquals(StartDecision.RATIONALE, TunerReducer.startDecision(denied, false, true))
        assertEquals(StartDecision.BLOCKED, TunerReducer.startDecision(denied, false, false))
    }

    @Test
    fun `rationale se muestra y se descarta`() {
        val shown = reduce(initial, TunerMutation.RationaleShown)
        assertTrue(shown.showRationale)
        val dismissed = reduce(shown, TunerMutation.RationaleDismissed)
        assertFalse(dismissed.showRationale)
        assertEquals(MicState.DENIED, dismissed.mic)
    }

    @Test
    fun `Pitch conserva los cents sin clamp`() {
        val s = reduce(initial.copy(isListening = true), TunerMutation.Reading(pitch(800.0)))
        assertEquals(800.0, (s.reading as TunerReading.Pitch).cents, 0.0)
        assertFalse(s.isInTune)
    }

    @Test
    fun `isInTune con umbral 2_5`() {
        assertTrue(reduce(initial, TunerMutation.Reading(pitch(2.5))).isInTune)
        assertTrue(reduce(initial, TunerMutation.Reading(pitch(-2.5))).isInTune)
        assertFalse(reduce(initial, TunerMutation.Reading(pitch(2.6))).isInTune)
        assertFalse(reduce(initial, TunerMutation.Reading(TunerReading.NoPitch)).isInTune)
    }

    @Test
    fun `fallos mapean a error y paran la escucha`() {
        val listening = initial.copy(isListening = true)
        val busy = reduce(listening, TunerMutation.Failed(TunerFailure.MicBusy))
        assertEquals(TunerError.MIC_BUSY, busy.error)
        assertFalse(busy.isListening)
        val unavailable = reduce(listening, TunerMutation.Failed(TunerFailure.MicUnavailable))
        assertEquals(TunerError.MIC_UNAVAILABLE, unavailable.error)
        val other = reduce(listening, TunerMutation.Failed(IllegalStateException()))
        assertEquals(TunerError.MIC_UNAVAILABLE, other.error)
        val denied = reduce(listening, TunerMutation.Failed(TunerFailure.MicPermissionDenied))
        assertEquals(MicState.DENIED, denied.mic)
        assertNull(denied.error)
    }

    @Test
    fun `escuchar limpia error y lectura y apaga la referencia`() {
        val s = reduce(
            initial.copy(error = TunerError.MIC_BUSY, isPlayingReference = true, reading = pitch(1.0)),
            TunerMutation.ListeningStarted
        )
        assertTrue(s.isListening)
        assertFalse(s.isPlayingReference)
        assertNull(s.error)
        assertEquals(MicState.GRANTED, s.mic)
        assertEquals(TunerReading.Idle, s.reading)
    }

    @Test
    fun `parar deja lectura Idle`() {
        val s = reduce(initial.copy(isListening = true, reading = pitch(1.0)), TunerMutation.ListeningStopped)
        assertFalse(s.isListening)
        assertEquals(TunerReading.Idle, s.reading)
    }

    @Test
    fun `la referencia no suena mientras se escucha`() {
        assertFalse(reduce(initial.copy(isListening = true), TunerMutation.ReferencePlaying(true)).isPlayingReference)
        assertTrue(reduce(initial, TunerMutation.ReferencePlaying(true)).isPlayingReference)
    }

    @Test
    fun `instrumento del perfil solo aplica si no se ha elegido uno local`() {
        val loaded = reduce(initial, TunerMutation.ProfileInstrument(Instrument.CELLO))
        assertEquals(Instrument.CELLO, loaded.instrument)
        val local = reduce(loaded, TunerMutation.InstrumentSelected(Instrument.VIOLA))
        assertEquals(Instrument.VIOLA, local.instrument)
        assertEquals(Instrument.VIOLA, reduce(local, TunerMutation.ProfileInstrument(Instrument.CELLO)).instrument)
    }

    @Test
    fun `cambiar de instrumento reinicia cuerda y lectura`() {
        val s = reduce(
            initial.copy(instrument = Instrument.VIOLIN, selectedString = 2, reading = pitch(1.0)),
            TunerMutation.InstrumentSelected(Instrument.CELLO)
        )
        assertNull(s.selectedString)
        assertEquals(TunerReading.Idle, s.reading)
        assertEquals(4, s.strings?.size)
    }

    @Test
    fun `OTHER es cromatico`() {
        assertNull(reduce(initial, TunerMutation.ProfileInstrument(Instrument.OTHER)).strings)
    }

    @Test
    fun `SelectString manual y auto`() {
        val violin = initial.copy(instrument = Instrument.VIOLIN)
        assertEquals(1, reduce(violin, TunerMutation.StringSelected(1)).selectedString)
        assertNull(reduce(violin.copy(selectedString = 1), TunerMutation.StringSelected(null)).selectedString)
    }

    @Test
    fun `cuerda fuera de rango o en cromatico se ignora`() {
        val violin = initial.copy(instrument = Instrument.VIOLIN)
        assertNull(reduce(violin, TunerMutation.StringSelected(4)).selectedString)
        assertNull(reduce(violin, TunerMutation.StringSelected(-1)).selectedString)
        assertNull(reduce(initial.copy(instrument = Instrument.OTHER), TunerMutation.StringSelected(0)).selectedString)
    }

    @Test
    fun `un TunerFailure ajeno al micro es UNKNOWN`() {
        assertEquals(TunerError.UNKNOWN, reduce(initial, TunerMutation.Failed(TunerFailure.PresetLimitReached)).error)
    }

    @Test
    fun `ocultar el rationale no cambia el micro`() {
        val s = reduce(initial.copy(showRationale = true), TunerMutation.RationaleHidden)
        assertFalse(s.showRationale)
        assertEquals(MicState.UNKNOWN, s.mic)
    }

    @Test
    fun `con permiso concedido antes y ahora retirado se vuelve a pedir`() {
        assertEquals(
            StartDecision.REQUEST,
            TunerReducer.startDecision(initial.copy(mic = MicState.GRANTED), false, false)
        )
    }
}
