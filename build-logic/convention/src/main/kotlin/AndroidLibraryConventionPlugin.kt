import com.android.build.api.dsl.LibraryExtension
import com.violinstudio.buildlogic.COMPILE_SDK
import com.violinstudio.buildlogic.JAVA_VERSION
import com.violinstudio.buildlogic.MIN_SDK
import com.violinstudio.buildlogic.TARGET_SDK
import com.violinstudio.buildlogic.applyKtlintGradle
import com.violinstudio.buildlogic.configureAndroidKtlintCli
import com.violinstudio.buildlogic.configureJUnit5
import com.violinstudio.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            applyKtlintGradle()
            extensions.configure<LibraryExtension> {
                compileSdk = COMPILE_SDK
                defaultConfig {
                    minSdk = MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }
                buildTypes.getByName("debug") { enableUnitTestCoverage = true }
                testOptions {
                    // Sin esto los tests de Robolectric de las librerías heredarían COMPILE_SDK como target.
                    targetSdk = TARGET_SDK
                    unitTests.isIncludeAndroidResources = true
                }
            }
            configureKotlinToolchain()
            configureJUnit5()
            configureAndroidKtlintCli()
        }
    }
}
