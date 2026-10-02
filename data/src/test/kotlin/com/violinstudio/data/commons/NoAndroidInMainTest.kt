package com.violinstudio.data.commons

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `data` se prueba con `unitTests.isReturnDefaultValues = true` (el SDK de Firebase llama a `android.*` al
 * construir sus excepciones). Eso haría que una llamada Android en código de producción "pasara" en los tests
 * sin ejecutar nada real, así que el código principal no puede importar `android.*`.
 */
class NoAndroidInMainTest {
    /** Imports permitidos explícitamente (ruta relativa a `src/main/kotlin` -> import). Hoy ninguno. */
    private val allowList = emptySet<Pair<String, String>>()

    @Test
    fun `el codigo principal de data no importa android`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "no se encontró ${root.absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().filter { it.trim().startsWith("import android.") }
                    .map { file.relativeTo(root).path.replace('\\', '/') to it.trim().removePrefix("import ") }
            }
            .filterNot { it in allowList }
            .toList()
        assertEquals(emptyList<Pair<String, String>>(), offenders)
    }
}
