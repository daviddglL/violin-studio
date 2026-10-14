package com.violinstudio.ui.feature.tuner.view

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Paridad es/en sobre TODAS las claves de la app: ninguna cadena puede caer al español en un móvil en inglés. */
class StringsParityTest {
    private val entry = Regex("""<string name="([^"]+)">(.*)</string>""")
    private val placeholders = Regex("""%\d\$[sd]""")

    private fun load(dir: String) = File("src/main/res/$dir/strings.xml").readLines()
        .mapNotNull { entry.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `todas las claves coinciden entre es y en con los mismos placeholders`() {
        val es = load("values")
        val en = load("values-en")
        assertTrue(es.size > 250, "se esperaban todas las claves, hay ${es.size}")
        assertTrue(es.keys.any { it.startsWith("consent_") }, "faltan las cadenas de consentimiento")
        assertEquals(es.keys, en.keys)
        val link = listOf(
            "link_redeem_notice_body",
            "link_redeem_notice_checkbox",
            "link_error_rate_limited",
            "link_unlink_dialog_body",
            "home_redeem_card",
            "home_my_teacher_card",
            "home_students_card",
            "home_codes_card",
            "teacher_unlink_dialog_body",
            "teacher_code_created_warning"
        )
        assertEquals(emptyList<String>(), link.filterNot { it in es.keys }, "faltan cadenas de vinculo")
        for (key in es.keys) {
            assertEquals(
                placeholders.findAll(es.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(en.getValue(key)).map { it.value }.toList(),
                key
            )
        }
    }

    @Test
    fun `las cadenas de tareas existen en es y en y se traducen`() {
        val es = load("values")
        val en = load("values-en")
        val keys = es.keys.filter { it.startsWith("assignment_") || it == "home_assignments_card" }
        assertTrue(keys.size >= 40, "se esperaban las cadenas de tareas, hay ${keys.size}")
        assertEquals(emptyList<String>(), keys.filterNot { it in en.keys }, "faltan en values-en")
        // Salvo el contador numerico, ninguna cadena de tareas queda sin traducir (igual en ambos idiomas).
        val untranslated = keys.filter { es.getValue(it) == en.getValue(it) && it != "assignment_counter" }
        assertEquals(emptyList<String>(), untranslated)
    }

    @Test
    fun `el fichero de cadenas no tiene entradas que el analizador no vea`() {
        for (dir in listOf("values", "values-en")) {
            val declared = File("src/main/res/$dir/strings.xml").readLines().count { it.contains("<string ") }
            assertEquals(declared, load(dir).size, "$dir: hay cadenas multilinea o mal formadas")
        }
    }

    @Test
    fun `values y values-en tienen los mismos ficheros de recursos`() {
        val es = File("src/main/res/values").list().orEmpty().toSet()
        val en = File("src/main/res/values-en").list().orEmpty().toSet()
        assertEquals(es, en)
    }

    @Test
    fun `ninguna traduccion queda vacia`() {
        val en = load("values-en")
        assertEquals(emptyList<String>(), en.filterValues { it.isBlank() }.keys.toList())
    }

    @Test
    fun `el castellano no usa el anglicismo preset`() {
        val offenders = load("values").filterValues { it.contains("preset", ignoreCase = true) }.keys.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `la linea base de lint ya no oculta ninguna traduccion que falte`() {
        val baseline = File("lint-baseline.xml")
        assertFalse(baseline.exists() && baseline.readText().contains("MissingTranslation"))
    }
}
