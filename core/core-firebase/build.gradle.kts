plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.hilt)
}

android { namespace = "com.violinstudio.core.firebase" }

dependencies {
    api(platform(libs.firebase.bom))
    api(libs.firebase.functions)
}
