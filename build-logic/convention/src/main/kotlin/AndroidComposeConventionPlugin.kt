import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.violinstudio.buildlogic.lib
import com.violinstudio.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType

/** Aplicar después de violin.android.application o violin.android.library. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
            extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true
            dependencies {
                val bom = platform(libs.lib("compose-bom"))
                "implementation"(bom)
                "testImplementation"(bom)
                "androidTestImplementation"(bom)
                "implementation"(libs.lib("compose-ui"))
                "implementation"(libs.lib("compose-ui-graphics"))
                "implementation"(libs.lib("compose-ui-tooling-preview"))
                "implementation"(libs.lib("compose-material3"))
                "debugImplementation"(libs.lib("compose-ui-tooling"))
                "debugImplementation"(libs.lib("compose-ui-test-manifest"))
                // Tests de UI con Robolectric/Roborazzi: JUnit4 vía vintage engine.
                "testImplementation"(libs.lib("compose-ui-test-junit4"))
                "testImplementation"(libs.lib("robolectric"))
                "testImplementation"(libs.lib("roborazzi"))
                "testImplementation"(libs.lib("roborazzi-compose"))
                "testImplementation"(libs.lib("roborazzi-junit-rule"))
                "testImplementation"(libs.lib("junit4"))
                "testImplementation"(libs.lib("androidx-test-ext-junit"))
                "testRuntimeOnly"(libs.lib("junit-vintage-engine"))
            }
        }
    }
}
