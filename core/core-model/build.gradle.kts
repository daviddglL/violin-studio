import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.jvm.library)
}

configureCoverage(classPaths = listOf("com/violinstudio/core/model/**"))
