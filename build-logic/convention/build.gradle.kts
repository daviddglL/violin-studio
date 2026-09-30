plugins {
    `kotlin-dsl`
}

group = "com.violinstudio.buildlogic"

// build-logic plugin classes load directly into the Gradle daemon's JVM (JDK 17 in this
// environment), not into a forked worker with a toolchain — unlike app/library modules,
// which use configureKotlinToolchain() (JDK 21) and compile in their own worker process.
kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "violin.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "violin.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "violin.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "violin.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "violin.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
