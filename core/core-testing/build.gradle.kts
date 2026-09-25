plugins {
    alias(libs.plugins.violin.android.library)
}

android { namespace = "com.violinstudio.core.testing" }

dependencies {
    api(project(":core:core-mvi"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit.jupiter.api)
    api(libs.turbine)
}
