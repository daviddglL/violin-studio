import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
}

android { namespace = "com.violinstudio.core.mvi" }

configureCoverage(classPaths = listOf("com/violinstudio/core/mvi/**"))

dependencies {
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.kotlinx.coroutines.core)
}
