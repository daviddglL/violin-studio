package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import com.violinstudio.domain.feature.tuner.failure.TunerField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TunerValuesTest {
    @ParameterizedTest
    @ValueSource(doubles = [415.0, 440.0, 442.0, 466.0])
    fun `acepta diapasones validos`(hz: Double) {
        assertEquals(hz, ReferencePitch(hz).hz)
    }

    @ParameterizedTest
    @ValueSource(doubles = [414.9, 466.1, 0.0, -440.0, Double.NaN, Double.POSITIVE_INFINITY])
    fun `rechaza diapasones invalidos con InvalidConfig`(hz: Double) {
        assertEquals(
            TunerFailure.InvalidConfig(TunerField.REFERENCE_PITCH),
            assertThrows<TunerFailure> { ReferencePitch(hz) }
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [25, 50, 200])
    fun `acepta maxCents validos`(v: Int) {
        assertEquals(v, MaxCents(v).value)
    }

    @ParameterizedTest
    @ValueSource(ints = [24, 201, 0, -50])
    fun `rechaza maxCents invalidos con InvalidConfig`(v: Int) {
        assertEquals(TunerFailure.InvalidConfig(TunerField.MAX_CENTS), assertThrows<TunerFailure> { MaxCents(v) })
    }

    @Test
    fun `los valores por defecto son 440 Hz y 50 cents`() {
        assertEquals(440.0, ReferencePitch.DEFAULT.hz)
        assertEquals(50, MaxCents.DEFAULT.value)
    }

    @Test
    fun `TunerFailure cubre todos los fallos`() {
        val all = listOf(
            TunerFailure.MicPermissionDenied,
            TunerFailure.MicUnavailable,
            TunerFailure.MicBusy,
            TunerFailure.AudioOutputUnavailable,
            TunerFailure.InvalidConfig(TunerField.LABEL),
            TunerFailure.PresetLimitReached
        )
        assertEquals(6, all.map { it::class }.toSet().size)
    }

    @Test
    fun `create devuelve Result con exito o el fallo con su campo`() {
        assertEquals(442.0, ReferencePitch.create(442.0).getOrThrow().hz)
        assertEquals(
            TunerFailure.InvalidConfig(TunerField.REFERENCE_PITCH),
            ReferencePitch.create(500.0).exceptionOrNull()
        )
        assertEquals(60, MaxCents.create(60).getOrThrow().value)
        assertEquals(TunerFailure.InvalidConfig(TunerField.MAX_CENTS), MaxCents.create(10).exceptionOrNull())
        assertTrue(TuningConfiguration.create("i", "ok", 440.0, 50).isSuccess)
        assertEquals(
            TunerFailure.InvalidConfig(TunerField.LABEL),
            TuningConfiguration.create("i", "", 440.0, 50).exceptionOrNull()
        )
        assertEquals(
            TunerFailure.InvalidConfig(TunerField.REFERENCE_PITCH),
            TuningConfiguration.create("i", "ok", 100.0, 50).exceptionOrNull()
        )
        assertEquals(
            TunerFailure.InvalidConfig(TunerField.MAX_CENTS),
            TuningConfiguration.create("i", "ok", 440.0, 1).exceptionOrNull()
        )
    }
}
