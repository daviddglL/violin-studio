package com.violinstudio.domain.feature.tuner.model

import com.violinstudio.domain.feature.profile.model.Instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StringSetTest {
    private fun midis(i: Instrument) = StringSet.of(i)?.map { it.midi }

    @Test
    fun `violin G3 D4 A4 E5`() = assertEquals(listOf(55, 62, 69, 76), midis(Instrument.VIOLIN))

    @Test
    fun `viola C3 G3 D4 A4`() = assertEquals(listOf(48, 55, 62, 69), midis(Instrument.VIOLA))

    @Test
    fun `cello C2 G2 D3 A3`() = assertEquals(listOf(36, 43, 50, 57), midis(Instrument.CELLO))

    @Test
    fun `contrabajo E1 A1 D2 G2`() = assertEquals(listOf(28, 33, 38, 43), midis(Instrument.DOUBLE_BASS))

    @Test
    fun `OTHER no tiene cuerdas`() = assertNull(StringSet.of(Instrument.OTHER))
}
