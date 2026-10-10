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

        val libs = versionCatalog()
        val jvmTarget = JvmTarget.fromTarget(libs.version("jvmTarget"))

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
                versionCode = appVersionCode()
                versionName = appVersionName()
            }
            val signing = releaseSigning()
            if (signing != null) {
                signingConfigs.create("release") {
                    storeFile = signing.storeFile
                    storePassword = signing.storePassword
                    keyAlias = signing.keyAlias
                    keyPassword = signing.keyPassword
                }
            }
            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                }
                release {
                    isMinifyEnabled = false
                    if (signing != null) signingConfig = signingConfigs.getByName("release")
                }
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
                // OldTargetApi would go red on every PR the moment CI's own SDK image
                // gets a newer platform; a targetSdk bump is a deliberate change, not
                // something the runner's SDK version should force.
                disable +=
                    setOf(
                        "GradleDependency",
                        "AndroidGradlePluginVersion",
                        "NewerVersionAvailable",
                        "ObsoleteSdkInt",
                        "OldTargetApi",
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
