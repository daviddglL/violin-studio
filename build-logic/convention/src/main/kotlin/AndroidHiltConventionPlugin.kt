import com.violinstudio.buildlogic.lib
import com.violinstudio.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                "implementation"(libs.lib("hilt-android"))
                "ksp"(libs.lib("hilt-compiler"))
            }
        }
    }
}
