package com.violinstudio.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register

/**
 * Lints the Kotlin sources under `src` of Android modules (main, test, androidTest, debug,
 * release, dev... any source set that happens to exist) with the ktlint CLI directly, wired
 * into `check`.
 *
 * Android modules also apply org.jlleitschuh.gradle.ktlint (for the `.kts` build-script check),
 * but that plugin discovers Kotlin source sets through the standalone
 * `org.jetbrains.kotlin.android` Gradle plugin's `KotlinSourceSet` extension. AGP 9.2.1's
 * built-in Kotlin support never applies that plugin (only `configureKotlinToolchain()`'s
 * `KotlinProjectExtension`, which is a different extension), so on Android modules
 * ktlint-gradle silently finds zero Kotlin source sets: `ktlintCheck` and `check` stay green
 * while the Kotlin sources under `src` are never linted. This task closes that gap. JVM modules
 * are untouched and keep using ktlint-gradle's own per-source-set tasks, which work correctly
 * there.
 *
 * Note for anyone editing this KDoc: Kotlin block comments nest (unlike Java or C), so writing
 * the glob pattern itself in here (slash, star, star) opens a second, inner comment that the
 * closing marker below only partially closes -- it silently swallows the rest of the file into
 * the comment. Describe the glob in prose instead of spelling it out literally.
 *
 * Pinned to ktlint-cli 1.0.1: the exact version org.jlleitschuh.gradle.ktlint:12.2.0 resolves by
 * default for JVM modules (confirmed via `./gradlew :core:core-model:dependencies --configuration
 * ktlint`), so both linting paths enforce identical rules. `.editorconfig` is honoured
 * automatically -- ktlint walks up from each linted file to find it -- no flag needed.
 */
fun Project.configureAndroidKtlintCli() {
    val ktlintClasspath = configurations.detachedConfiguration(libs.lib("ktlint-cli").get())
    val projectDirectory = layout.projectDirectory
    // Kept as a concatenation (not a single literal) so a "slash star" pair never appears
    // together in the source text; see the KDoc note above about Kotlin's nested comments.
    val sourceGlob = "src/**" + "/" + "*.kt"

    val ktlintCheckSources = tasks.register<JavaExec>("ktlintCheckAndroidSources") {
        group = "verification"
        description = "Lints this module's Kotlin sources with the ktlint CLI (see " +
            "KtlintCli.kt for why this bypasses org.jlleitschuh.gradle.ktlint on Android " +
            "modules)."
        classpath = ktlintClasspath
        mainClass.set("com.pinterest.ktlint.Main")
        workingDir(projectDirectory)
        args(sourceGlob)
        inputs.files(fileTree(projectDirectory) { include(sourceGlob) })
            .withPropertyName("ktlintSources")
            .skipWhenEmpty()
        inputs.file(rootProject.layout.projectDirectory.file(".editorconfig"))
            .withPropertyName("editorconfig")
            .optional()
    }

    tasks.named("check") { dependsOn(ktlintCheckSources) }
}
