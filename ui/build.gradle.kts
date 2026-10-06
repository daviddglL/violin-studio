import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.compose)
    alias(libs.plugins.violin.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.violinstudio.ui"
}

configureCoverage(
    classPaths = listOf("com/violinstudio/ui/commons/mvi/**", "com/violinstudio/ui/**/*Reducer*.class")
)

dependencies {
    implementation(project(":domain"))

    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}
