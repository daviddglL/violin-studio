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
        // Robolectric 4.17 con android-all 36 crea ApplicationSharedMemory y toca FileDescriptor por SharedSecrets;
        // sin este export en JDK 21 todos los tests de Robolectric fallan al arrancar el sandbox.
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
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
