plugins {
    alias(libs.plugins.violin.android.application)
    alias(libs.plugins.violin.android.compose)
    alias(libs.plugins.violin.android.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// IP del PC con los emuladores de Firebase. Para móvil físico: -Pviolin.emulatorHost=192.168.x.x
val emulatorHost = providers.gradleProperty("violin.emulatorHost").getOrElse("10.0.2.2")
val keystoreFile = file(
    providers.environmentVariable("KEYSTORE_PATH").getOrElse(rootProject.file("keystore/upload.jks").path)
)

android {
    namespace = "com.violinstudio"

    defaultConfig {
        applicationId = "com.violinstudio"
        testInstrumentationRunner = "com.violinstudio.HiltTestRunner"
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.getOrElse(1)
        versionName = "0.1.0"
    }

    productFlavors {
        getByName("dev") {
            buildConfigField("boolean", "USE_EMULATORS", "true")
            buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
            buildConfigField("boolean", "CRASHLYTICS_ENABLED", "false")
        }
        getByName("prod") {
            buildConfigField("boolean", "USE_EMULATORS", "false")
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            buildConfigField("boolean", "CRASHLYTICS_ENABLED", "true")
        }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").getOrElse("upload")
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Solo CI debe subir el mapping de ofuscación; una release local no tiene por qué.
            firebaseCrashlytics {
                mappingFileUploadEnabled = providers.environmentVariable("CI").isPresent
            }
        }
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.firebase.firestore)
    kspAndroidTest(libs.hilt.compiler)
}
