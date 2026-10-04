import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.ComposePlugin
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Applies the Compose Multiplatform plugin + the Kotlin Compose compiler plugin, and names each
 * module's generated `Res` class after its path, e.g. `:feature:compose` gets
 * `com.verdenroz.corvid.feature.compose.resources`.
 */
class ComposeMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.compose")
            apply("org.jetbrains.kotlin.plugin.compose")
        }
        // Resolving a string resource on the desktop JVM asks Skiko for the system theme.
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.named("desktopTest") { dependencies { implementation(ComposePlugin.DesktopDependencies.currentOs) } }
            }
        }
        extensions.configure<ComposeExtension> {
            extensions.configure<ResourcesExtension> {
                packageOfResClass = "com.verdenroz.corvid." + path.removePrefix(":").replace(':', '.').replace("-", "") + ".resources"
            }
        }
    }
}
