import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Shared config for the Android application module. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
        }

        val version = providers.gradleProperty("corvid.version").getOrElse("0.1.0")
        val libs = versionCatalog()
        val jvmTarget = JvmTarget.fromTarget(libs.version("jvmTarget"))

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
                versionCode = versionCodeOf(version)
                versionName = version
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTarget.target)
                targetCompatibility = JavaVersion.toVersion(jvmTarget.target)
            }
            lint {
                warningsAsErrors = true
                // Every version here is pinned on purpose; freshness checks
                // would fail warningsAsErrors on every pin, always. ObsoleteSdkInt
                // misreads mipmap-anydpi-v26: the qualifier picks the adaptive-icon
                // XML schema, not a runtime SDK check, so minSdk can't retire it.
                disable +=
                    setOf(
                        "GradleDependency",
                        "AndroidGradlePluginVersion",
                        "NewerVersionAvailable",
                        "ObsoleteSdkInt",
                    )
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                this.jvmTarget.set(jvmTarget)
                allWarningsAsErrors.set(true)
            }
        }
    }
}

/**
 * Android compares builds by version code alone, so it has to rise with the
 * version name. Allows up to 99 minor and 99 patch releases per major.
 */
private fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split(".").map(String::toInt)
    return major * 10_000 + minor * 100 + patch
}
