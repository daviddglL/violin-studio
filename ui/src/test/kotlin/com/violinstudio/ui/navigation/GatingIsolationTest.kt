package com.violinstudio.ui.navigation

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * El gating vive solo en `navigation` y en las features que conocen la sesión: las rutas de negocio no contienen
 * lógica de sesión propia, así que revertir 5a/5b/6a/6b deja `HomeDestination` accesible sin tocarlas.
 */
class GatingIsolationTest {
    private val features = File("src/main/kotlin/com/violinstudio/ui/feature")

    private fun businessSources(): List<File> =
        features.listFiles { f -> f.isDirectory && f.name !in SESSION_AWARE_FEATURES }
            .orEmpty().flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

    @Test
    fun `business features contain no session logic`() {
        val sources = businessSources()
        assertTrue(sources.isNotEmpty(), "no business sources found: the scan would be vacuous")
        val violations = sources.flatMap { file -> violationsIn(file.readText()).map { "${file.name}: $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `scanner flags session imports, including wildcards, and ignores comments`() {
        assertEquals(emptyList<String>(), violationsIn("// SessionState AuthRepository\n/* rootRoute */\nclass A"))
        assertTrue(violationsIn("import com.violinstudio.domain.feature.session.*\nclass A").isNotEmpty())
        val consentImport = "import com.violinstudio.domain.feature.consent.repository.ConsentRepository"
        val accountImport = "import com.violinstudio.domain.feature.account.usecase.DeleteAccountUseCase"
        assertTrue(violationsIn(consentImport).isNotEmpty())
        assertTrue(violationsIn(accountImport).isNotEmpty())
        assertTrue(violationsIn("import com.violinstudio.ui.navigation.HomeDestination").isNotEmpty())
        assertTrue(violationsIn("val s: com.violinstudio.domain.feature.session.SessionState? = null").isNotEmpty())
        assertTrue(violationsIn("fun f(r: SessionStateResolver) = r").isNotEmpty())
        assertEquals(
            emptyList<String>(),
            violationsIn("import com.violinstudio.domain.feature.health.usecase.CheckHealthUseCase\nclass A")
        )
    }

    companion object {
        /**
         * Únicas features que pueden conocer la sesión (nombres de los paquetes previstos para 5b/6a/6b). Añadir
         * una feature aquí es una decisión consciente de arquitectura (p. ej. 8a para borrar cuenta).
         */
        val SESSION_AWARE_FEATURES = setOf("session", "auth", "onboarding", "consent", "guardian")

        private val forbiddenPackages = listOf(
            "com.violinstudio.domain.feature.session",
            "com.violinstudio.domain.feature.consent",
            "com.violinstudio.domain.feature.account",
            "com.violinstudio.domain.feature.auth",
            "com.violinstudio.ui.navigation",
            "com.violinstudio.ui.feature.session"
        )
        private val forbiddenSymbols = listOf(
            "SessionState", "SessionStateResolver", "SessionViewModel", "SessionIntent", "AuthRepository",
            "ConsentRepository", "AccountRepository", "SignOutUseCase", "rootRoute"
        )

        private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val lineComment = Regex("""//[^\n]*""")

        fun violationsIn(source: String): List<String> {
            val code = source.replace(blockComment, "").replace(lineComment, "")
            val packages = forbiddenPackages.filter { code.contains(it) }.map { "references package $it" }
            val symbols = forbiddenSymbols.filter { Regex("""\b$it\b""").containsMatchIn(code) }
                .map { "references $it" }
            return packages + symbols
        }
    }
}
