package com.violinstudio.data.commons.erasure

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D8: la config local se conserva al cerrar sesion; solo el borrado de cuenta puede invocar a los erasers. */
class ErasureArchitectureTest {
    private val allowed = listOf(
        "com/violinstudio/data/feature/account/repository/AccountRepositoryImpl.kt",
        "com/violinstudio/data/commons/erasure/",
        "com/violinstudio/data/commons/di/ErasureModule.kt"
    )

    @Test
    fun `LocalUserDataEraser solo lo referencian el borrado de cuenta y el modulo de erasers`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "no se encontró ${root.absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.extension == "kt" && "LocalUserDataEraser" in it.readText() }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filterNot { path -> allowed.any { path.startsWith(it) } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
