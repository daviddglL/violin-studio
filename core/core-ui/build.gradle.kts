plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.compose)
}

android { namespace = "com.violinstudio.core.ui" }

dependencies {
    api(libs.androidx.lifecycle.runtime.compose)
}
