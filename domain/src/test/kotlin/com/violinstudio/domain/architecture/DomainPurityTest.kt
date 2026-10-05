package com.violinstudio.domain.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainPurityTest {
    private val sources = File("src/main/kotlin")

    @Test
    fun `domain no importa android`() {
        val files = sources.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty()) { "no sources found from ${File(".").absolutePath}" }
        val offenders = files.filter { f -> f.readLines().any { it.trimStart().startsWith("import android.") } }
        assertEquals(emptyList<File>(), offenders)
    }
}
