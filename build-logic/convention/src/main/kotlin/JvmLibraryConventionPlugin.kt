import com.violinstudio.buildlogic.applyKtlintGradle
import com.violinstudio.buildlogic.configureJUnit5
import com.violinstudio.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            applyKtlintGradle()
            configureKotlinToolchain()
            configureJUnit5()
        }
    }
}
