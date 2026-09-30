package com.violinstudio.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26
internal const val JDK = 21
internal val JAVA_VERSION = JavaVersion.VERSION_21

/**
 * jvmToolchain(21) alone only steers Kotlin's own compile tasks. Plain JavaCompile tasks (e.g.
 * the ones AGP wires up for annotation processors, notably Hilt's generated-component javac step)
 * still run on whatever JDK started the Gradle daemon, so they fail with "invalid source release:
 * 21" whenever that daemon JDK is older than 21. Pin every JavaCompile task to a real JDK 21
 * toolchain (auto-provisioned by the foojay resolver if not already installed) so this holds
 * regardless of the host JDK.
 */
internal fun Project.configureKotlinToolchain() {
    extensions.configure<KotlinProjectExtension> { jvmToolchain(JDK) }
    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    tasks.withType<JavaCompile>().configureEach {
        javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(JDK)) })
    }
}
