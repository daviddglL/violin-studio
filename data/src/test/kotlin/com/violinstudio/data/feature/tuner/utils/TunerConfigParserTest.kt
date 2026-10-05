package com.violinstudio.data.feature.tuner.utils

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunerConfigParserTest {
    private val baroque = TuningConfiguration("p1", "Barroco", ReferencePitch(415.0), MaxCents(75))

    @Test
    fun `escribir y leer devuelve la misma config con esquema v1 y claves prefijadas por uid`() {
        val config = TunerConfig(ReferencePitch(442.0), MaxCents(100), listOf(baroque), "p1")
        val prefs = mutablePreferencesOf()
        TunerConfigParser.write(prefs, "uidA", config)
        assertEquals(config, TunerConfigParser.read(prefs, "uidA"))
        assertEquals(1, prefs[UserKeys.tunerVersion("uidA")])
        assertTrue(prefs.asMap().keys.all { it.name.startsWith(UserKeys.prefix("uidA")) })
        assertEquals(TunerConfig(), TunerConfigParser.read(prefs, "uidB"))
    }

    @Test
    fun `valores fuera de rango o JSON corrupto caen a defectos sin lanzar`() {
        val prefs = mutablePreferencesOf()
        prefs[UserKeys.referenceHz("u")] = 999.0
        prefs[UserKeys.maxCents("u")] = 5
        prefs[UserKeys.presets("u")] = "{no es json"
        assertEquals(TunerConfig(), TunerConfigParser.read(prefs, "u"))
    }

    @Test
    fun `un preset invalido se descarta, el resto se conserva y una seleccion huerfana se ignora`() {
        val prefs = mutablePreferencesOf()
        prefs[UserKeys.presets("u")] =
            """[{"id":"p1","label":"Barroco","referenceHz":415.0,"maxCents":75},""" +
            """{"id":"p2","label":"","referenceHz":440.0,"maxCents":50}]"""
        prefs[UserKeys.selectedPreset("u")] = "p2"
        assertEquals(TunerConfig(presets = listOf(baroque)), TunerConfigParser.read(prefs, "u"))
        prefs[stringPreferencesKey("u.u.tuner.selected_preset")] = "p1"
        assertEquals("p1", TunerConfigParser.read(prefs, "u").selectedPresetId)
    }
}
