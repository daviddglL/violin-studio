package com.violinstudio.ui.feature.tuner.view

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringsParityTest {
    private val entry = Regex("""<string name="((?:tuner_)[^"]+)">(.*)</string>""")

    private fun load(dir: String) = File("src/main/res/$dir/strings.xml").readLines()
        .mapNotNull { entry.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `las claves del afinador coinciden entre es y en con los mismos placeholders`() {
        val es = load("values")
        val en = load("values-en")
        assertTrue(es.isNotEmpty())
        assertEquals(es.keys, en.keys)
        val placeholders = Regex("""%\d\$[sd]""")
        for (key in es.keys) {
            assertEquals(
                placeholders.findAll(es.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(en.getValue(key)).map { it.value }.toList(),
                key
            )
        }
    }
}
