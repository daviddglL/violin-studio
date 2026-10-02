import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.hilt)
}

android { namespace = "com.violinstudio.data" }

configureCoverage(classPaths = listOf("com/violinstudio/data/**"))

dependencies {
    implementation(project(":domain"))

    api(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    api(libs.firebase.functions)
    implementation(libs.kotlinx.coroutines.play.services)
}
