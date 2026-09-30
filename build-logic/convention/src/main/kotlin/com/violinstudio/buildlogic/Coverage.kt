package com.violinstudio.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.api.tasks.testing.Test
import java.math.BigDecimal

private val COVERAGE_EXCLUDES = listOf(
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/di/**",
    "**/datasource/firebase/**",
    "**/*_Factory*.class",
    "**/*_HiltModules*.class",
    "**/*_MembersInjector*.class",
    "**/Hilt_*.class",
    "**/Dagger*.class",
    "**/*ComposableSingletons*.class",
)

/**
 * Registra `coverageVerification` (LINE >= [minimum]) sobre las clases que casan con [classPaths]
 * (patrones de ruta de .class, p. ej. para el paquete com.violinstudio.ui.commons.mvi) y la engancha a
 * `check`.
 *
 * Falla si no encuentra ninguna clase: JaCoCo aprueba en vacío cuando no hay clases, y eso
 * ocultaría un error de ruta (AGP 9 compila Kotlin en `intermediates/built_in_kotlinc`).
 */
fun Project.configureCoverage(
    classPaths: List<String>,
    variant: String = "debug",
    minimum: String = "0.80",
) {
    pluginManager.apply("jacoco")
    extensions.configure<JacocoPluginExtension> { toolVersion = libs.version("jacoco") }
    tasks.withType<Test>().configureEach {
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }

    val isJvm = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")
    val variantCap = variant.replaceFirstChar { it.uppercase() }
    val testTask = if (isJvm) "test" else "test${variantCap}UnitTest"
    val classRoots = if (isJvm) {
        listOf("classes/kotlin/main")
    } else {
        listOf("tmp/kotlin-classes/$variant", "intermediates/built_in_kotlinc/$variant")
    }
    val execPattern = if (isJvm) "jacoco/test.exec" else "outputs/unit_test_code_coverage/${variant}UnitTest/*.exec"
    val buildDir = layout.buildDirectory

    val verify = tasks.register<JacocoCoverageVerification>("coverageVerification") {
        group = "verification"
        description = "Cobertura de líneas >= $minimum en $classPaths"
        dependsOn(testTask)
        classDirectories.setFrom(
            fileTree(buildDir) {
                include(classRoots.flatMap { root -> classPaths.map { "$root/**/$it" } })
                exclude(COVERAGE_EXCLUDES)
            },
        )
        executionData.setFrom(fileTree(buildDir) { include(execPattern) })
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    this.minimum = BigDecimal(minimum)
                }
            }
        }
        doFirst {
            check(!classDirectories.asFileTree.isEmpty) {
                "coverageVerification: no hay clases en $classRoots que casen con $classPaths"
            }
        }
    }
    tasks.named("check") { dependsOn(verify) }
}
