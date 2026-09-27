import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared config for a feature module: KMP library + Compose plus the deps every
 * feature needs (designsystem/ui/domain, ViewModel, navigation, Koin).
 */
class KotlinMultiplatformFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("corvid.kmp.library")
            apply("corvid.compose.multiplatform")
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(alias: String) = libs.findLibrary(alias).get()

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.apply {
                commonMain.dependencies {
                    implementation(project(":core:model"))
                    implementation(project(":core:domain"))
                    implementation(project(":core:designsystem"))
                    implementation(project(":core:ui"))
                    implementation(lib("kotlinx-coroutines-core"))
                    implementation(lib("jetbrains-lifecycle-viewmodel"))
                    implementation(lib("jetbrains-lifecycle-viewmodel-compose"))
                    implementation(lib("jetbrains-lifecycle-runtime-compose"))
                    implementation(lib("jetbrains-navigation-compose"))
                    implementation(lib("koin-core"))
                    implementation(lib("koin-compose"))
                    implementation(lib("koin-compose-viewmodel"))
                }
            }
        }
    }
}
