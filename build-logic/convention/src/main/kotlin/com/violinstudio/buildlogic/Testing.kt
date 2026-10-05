package com.violinstudio.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import java.time.Duration

private const val TEST_TASK_TIMEOUT_MINUTES = 10L

/** JUnit5 en todos los tests JVM. Gradle 9 exige declarar junit-platform-launcher. */
internal fun Project.configureJUnit5() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Red de seguridad: un worker colgado se mata y la build FALLA con el test visible, en vez de agotar el job.
        timeout.set(Duration.ofMinutes(TEST_TASK_TIMEOUT_MINUTES))
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
    dependencies {
        "testImplementation"(libs.lib("junit-jupiter"))
        "testRuntimeOnly"(libs.lib("junit-platform-launcher"))
        "testImplementation"(libs.lib("kotlinx-coroutines-test"))
        "testImplementation"(libs.lib("turbine"))
        "testImplementation"(libs.lib("mockk"))
    }
}
