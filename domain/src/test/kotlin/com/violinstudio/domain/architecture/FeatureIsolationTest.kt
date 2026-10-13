package com.violinstudio.domain.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El vínculo y las tareas no dependen del flujo de sesión/consentimiento (REQ-LNK-10); `session` no se añade aquí. */
class FeatureIsolationTest {
    private val root = File("src/main/kotlin/com/violinstudio/domain/feature")

    private fun importsOf(feature: String): Map<File, List<String>> {
        val files = File(root, feature).walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty()) { "sin fuentes en ${File(root, feature).absolutePath}" }
        return files.associateWith { f -> f.readLines().filter { it.trimStart().startsWith("import ") } }
    }

    private fun offenders(feature: String, forbiddenPrefixes: List<String>): List<String> =
        importsOf(feature).flatMap { (file, imports) ->
            imports.filter { line -> forbiddenPrefixes.any { line.contains(it) } }.map { "${file.name}: $it" }
        }

    @Test
    fun `link y assignment no importan session ni consent`() {
        val forbidden = listOf("domain.feature.session.", "domain.feature.consent.")
        assertEquals(emptyList<String>(), offenders("link", forbidden))
        assertEquals(emptyList<String>(), offenders("assignment", forbidden))
    }

    @Test
    fun `link y assignment no se importan entre si`() {
        assertEquals(emptyList<String>(), offenders("assignment", listOf("domain.feature.link.")))
        assertEquals(emptyList<String>(), offenders("link", listOf("domain.feature.assignment.")))
    }

    @Test
    fun `link y assignment no usan android androidx ni dagger`() {
        val forbidden = listOf("import android.", "import androidx.", "import dagger.")
        assertEquals(emptyList<String>(), offenders("link", forbidden))
        assertEquals(emptyList<String>(), offenders("assignment", forbidden))
    }
}
