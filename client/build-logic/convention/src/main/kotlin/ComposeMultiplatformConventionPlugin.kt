import org.gradle.api.Plugin
import org.gradle.api.Project

/** Applies the Compose Multiplatform plugin + the Kotlin Compose compiler plugin. */
class ComposeMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.compose")
            apply("org.jetbrains.kotlin.plugin.compose")
        }
    }
}
