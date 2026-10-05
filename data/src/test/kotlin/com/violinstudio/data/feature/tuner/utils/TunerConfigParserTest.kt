package com.violinstudio.data.feature.tuner.utils

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.violinstudio.domain.feature.tuner.model.MaxCents
import com.violinstudio.domain.feature.tuner.model.ReferencePitch
import com.violinstudio.domain.feature.tuner.model.TunerConfig
import com.violinstudio.domain.feature.tuner.model.TuningConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
        prefs[UserKeys.selectedPreset("u")] = "p1"
        assertEquals("p1", TunerConfigParser.read(prefs, "u").selectedPresetId)
    }

    @Test
    fun `una clave con tipo equivocado cae a defectos sin lanzar`() {
        val prefs = mutablePreferencesOf()
        prefs[stringPreferencesKey("u.u.tuner.max_cents")] = "100"
        prefs[intPreferencesKey("u.u.tuner.reference_hz")] = 442
        prefs[intPreferencesKey("u.u.tuner.presets")] = 7
        prefs[stringPreferencesKey("u.u.tuner.v")] = "1"
        assertEquals(TunerConfig(), TunerConfigParser.read(prefs, "u"))
    }

    @Test
    fun `un esquema posterior se lee como defectos y se detecta`() {
        val prefs = mutablePreferencesOf()
        TunerConfigParser.write(prefs, "u", TunerConfig(maxCents = MaxCents(100)))
        assertFalse(TunerConfigParser.isNewerSchema(prefs, "u"))
        prefs[UserKeys.tunerVersion("u")] = UserKeys.SCHEMA_VERSION + 1
        assertTrue(TunerConfigParser.isNewerSchema(prefs, "u"))
        assertEquals(TunerConfig(), TunerConfigParser.read(prefs, "u"))
    }

    @Test
    fun `un campo extra o un elemento malo no pierden los presets validos`() {
        val prefs = mutablePreferencesOf()
        prefs[UserKeys.presets("u")] =
            """[{"id":"p1","label":"Barroco","referenceHz":415.0,"maxCents":75,"nuevo":true},""" +
            """{"id":"p2"},"basura",{"id":"p3","label":"Otro","referenceHz":440.0,"maxCents":50}]"""
        assertEquals(listOf("p1", "p3"), TunerConfigParser.read(prefs, "u").presets.map { it.id })
    }

    @Test
    fun `mas de 20 presets se truncan y con ids repetidos gana el primero`() {
        val prefs = mutablePreferencesOf()
        prefs[UserKeys.presets("u")] = (1..25).joinToString(",", "[", "]") {
            """{"id":"p$it","label":"L$it","referenceHz":440.0,"maxCents":50}"""
        }
        assertEquals(20, TunerConfigParser.read(prefs, "u").presets.size)
        prefs[UserKeys.presets("u")] =
            """[{"id":"d","label":"Primero","referenceHz":440.0,"maxCents":50},""" +
            """{"id":"d","label":"Segundo","referenceHz":440.0,"maxCents":50}]"""
        assertEquals(listOf("Primero"), TunerConfigParser.read(prefs, "u").presets.map { it.label })
    }

    @Test
    fun `un uid con punto se rechaza para que un prefijo nunca alcance a otro uid`() {
        assertThrows(IllegalArgumentException::class.java) { UserKeys.prefix("abc.def") }
    }
}
