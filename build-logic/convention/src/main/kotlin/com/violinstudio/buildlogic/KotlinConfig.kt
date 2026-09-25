package com.violinstudio.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26
internal const val JDK = 21
internal val JAVA_VERSION = JavaVersion.VERSION_21

internal fun Project.configureKotlinToolchain() {
    extensions.configure<KotlinProjectExtension> { jvmToolchain(JDK) }
}
