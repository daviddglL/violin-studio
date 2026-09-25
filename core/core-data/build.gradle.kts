import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.hilt)
}

android { namespace = "com.violinstudio.core.data" }

configureCoverage(classPaths = listOf("com/violinstudio/core/data/**"))

dependencies {
    api(project(":core:core-model"))
    implementation(project(":core:core-firebase"))
    implementation(libs.kotlinx.coroutines.play.services)
}
