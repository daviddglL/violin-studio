import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.jvm.library)
}

configureCoverage(classPaths = listOf("com/violinstudio/domain/**"))

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
}
