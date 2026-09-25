import com.android.build.api.dsl.ApplicationExtension
import com.violinstudio.buildlogic.COMPILE_SDK
import com.violinstudio.buildlogic.JAVA_VERSION
import com.violinstudio.buildlogic.MIN_SDK
import com.violinstudio.buildlogic.configureJUnit5
import com.violinstudio.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            extensions.configure<ApplicationExtension> {
                compileSdk = COMPILE_SDK
                defaultConfig {
                    minSdk = MIN_SDK
                    targetSdk = COMPILE_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }
                buildFeatures { buildConfig = true }
                buildTypes.getByName("debug") { enableUnitTestCoverage = true }
                testOptions { unitTests.isIncludeAndroidResources = true }
                flavorDimensions += "environment"
                productFlavors {
                    create("dev") {
                        dimension = "environment"
                        applicationIdSuffix = ".dev"
                        versionNameSuffix = "-dev"
                    }
                    create("prod") { dimension = "environment" }
                }
            }
            configureKotlinToolchain()
            configureJUnit5()
        }
    }
}
