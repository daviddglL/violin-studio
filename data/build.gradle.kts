import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.hilt)
}

// `isReturnDefaultValues`: el SDK de Firebase llama a android.* (TextUtils) al construir sus excepciones en los tests.
// Efecto secundario: cualquier android.* no mockeado "funciona" en silencio, por eso `NoAndroidInMainTest` prohibe
// imports de android.* en data/src/main (añadir a su lista blanca solo con motivo).
android {
    namespace = "com.violinstudio.data"
    // Las excepciones del SDK (FirebaseAuthException...) llaman a android.text.TextUtils en su constructor.
    testOptions { unitTests.isReturnDefaultValues = true }
}

configureCoverage(classPaths = listOf("com/violinstudio/data/**"))

dependencies {
    implementation(project(":domain"))

    api(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    api(libs.firebase.functions)
    implementation(libs.kotlinx.coroutines.play.services)
}
