package com.violinstudio.domain.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainPurityTest {
    private val sources = File("src/main/kotlin")

    @Test
    fun `domain no importa android androidx ni dagger`() {
        val files = sources.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty()) { "no sources found from ${File(".").absolutePath}" }
        // javax.inject se permite: domain lo usa para @Inject
        val forbidden = listOf("import android.", "import androidx.", "import dagger.")
        val offenders = files.filter { f ->
            f.readLines().any { line -> forbidden.any { line.trimStart().startsWith(it) } }
        }
        assertEquals(emptyList<File>(), offenders)
    }
}
