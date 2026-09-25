plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint) apply false
}

// Tareas de cobertura de todos los módulos con umbral. Cada task del plan que
// añade un módulo con umbral añade aquí su ruta.
tasks.register("coverage") {
    group = "verification"
    description = "Verifica el umbral de cobertura en todos los módulos que lo tienen."
    dependsOn(":core:core-model:coverageVerification")
}
