package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.tuner.failure.TunerFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
        assertSame(TunerFailure.InvalidConfig, assertThrows<TunerFailure> { ReferencePitch(hz) })
    }

    @ParameterizedTest
    @ValueSource(ints = [25, 50, 200])
    fun `acepta maxCents validos`(v: Int) {
        assertEquals(v, MaxCents(v).value)
    }

    @ParameterizedTest
    @ValueSource(ints = [24, 201, 0, -50])
    fun `rechaza maxCents invalidos con InvalidConfig`(v: Int) {
        assertSame(TunerFailure.InvalidConfig, assertThrows<TunerFailure> { MaxCents(v) })
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
            TunerFailure.InvalidConfig,
            TunerFailure.PresetLimitReached
        )
        assertEquals(6, all.map { it::class }.toSet().size)
    }
}
