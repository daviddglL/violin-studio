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
        val violations = sources.flatMap { file ->
            val allowed = ALLOWED_BY_FEATURE[file.relativeTo(features).path.substringBefore(File.separator)].orEmpty()
            violationsIn(file.readText(), allowed).map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `settings may use only its allowed consent and refresh symbols`() {
        val allowed = SETTINGS_ALLOWED
        val ok = allowed.joinToString(NL) { "import $it" }
        assertEquals(emptyList<String>(), violationsIn("${ok}${NL}class A", allowed))
        val base = "com.violinstudio"
        val forbidden = listOf(
            "$base.domain.feature.session.SessionState",
            "$base.domain.feature.session.SessionStateResolver",
            "$base.domain.feature.consent.repository.ConsentRepository",
            "$base.domain.feature.consent.usecase.AcceptPolicyUseCase",
            "$base.domain.feature.profile.repository.ProfileRepository",
            "$base.domain.feature.account.usecase.DeleteAccountUseCase",
            "$base.ui.navigation.HomeDestination"
        )
        for (import in forbidden) {
            val found = violationsIn("${ok}${NL}import $import${NL}class A", allowed)
            assertTrue(found.isNotEmpty(), "not caught: $import")
        }
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
         * una feature aquí es una decisión consciente de arquitectura; lo mínimo que necesite una feature de negocio
         * (p. ej. ajustes) va en [ALLOWED_BY_FEATURE].
         */
        val SESSION_AWARE_FEATURES = setOf("session", "auth", "onboarding", "consent", "guardian")

        private const val NL = "\n"
        private const val BASE = "com.violinstudio.domain.feature"

        /** Lo unico que ajustes necesita de consentimiento y sesion: revocar y pedir que la sesion se reevalue. */
        val SETTINGS_ALLOWED = listOf(
            "$BASE.consent.usecase.RevokeConsentUseCase",
            "$BASE.consent.failure.ConsentFailure",
            "$BASE.session.SessionRefreshTrigger"
        )
        val ALLOWED_BY_FEATURE = mapOf("settings" to SETTINGS_ALLOWED)

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

        private val repositoryPackage = Regex("""domain\.feature\.[a-z]+\.repository""")

        fun violationsIn(source: String, allowed: List<String> = emptyList()): List<String> {
            val stripped = source.replace(blockComment, "").replace(lineComment, "")
            val code = allowed.fold(stripped) { acc, name -> acc.replace(name, "") }
            val packages = forbiddenPackages.filter { code.contains(it) }.map { "references package $it" }
            val symbols = forbiddenSymbols.filter { Regex("""\b$it\b""").containsMatchIn(code) }
                .map { "references $it" }
            val repositories = listOf("references a repository").filter { repositoryPackage.containsMatchIn(code) }
            return packages + symbols + repositories
        }
    }
}
