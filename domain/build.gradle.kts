import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.jvm.library)
}

configureCoverage(classPaths = listOf("com/violinstudio/domain/**"))

dependencies {
    implementation(libs.javax.inject)
}
