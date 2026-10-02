package com.violinstudio.ui.navigation

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * El gating vive solo en `navigation` y en `feature/session`/`feature/auth`: las rutas de negocio no contienen
 * lógica de sesión propia, así que revertir 5a/5b/6a/6b deja `HomeDestination` accesible sin tocarlas.
 */
class GatingIsolationTest {
    private val features = File("src/main/kotlin/com/violinstudio/ui/feature")
    private val sessionFeatures = setOf("session", "auth")
    private val forbidden = listOf(
        "SessionState",
        "SessionViewModel",
        "SessionIntent",
        "ObserveSessionStateUseCase",
        "rootRoute",
        "ui.navigation",
        "SignOutUseCase"
    )

    private fun businessSources(): List<File> = features.listFiles { f -> f.isDirectory && f.name !in sessionFeatures }
        .orEmpty().flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

    @Test
    fun `business features contain no session logic`() {
        val sources = businessSources()
        assertTrue(sources.isNotEmpty(), "no business sources found: the scan would be vacuous")
        val violations = sources.flatMap { file ->
            val text = file.readText()
            forbidden.filter { it in text }.map { "${file.name} mentions $it" }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `the home route is gated by the navigation host, not by itself`() {
        val host = File("src/main/kotlin/com/violinstudio/ui/navigation/AppNavHost.kt").readText()
        assertTrue("composable<HomeDestination> { if (session is SessionState.Ready)" in host)
    }
}
