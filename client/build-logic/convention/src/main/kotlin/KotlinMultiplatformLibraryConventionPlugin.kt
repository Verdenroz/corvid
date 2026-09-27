import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Shared config for a KMP library targeting Android + JVM desktop. */
class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("com.android.library")
        }

        val libs = versionCatalog()
        fun lib(alias: String) = libs.findLibrary(alias).get()
        val jvmTarget = JvmTarget.fromTarget(libs.version("jvmTarget"))

        extensions.configure<KotlinMultiplatformExtension> {
            compilerOptions {
                allWarningsAsErrors.set(true)
                // KT-61573: expect/actual classes are still Beta; the warning
                // never resolves, so silence it instead of failing every build.
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
            androidTarget {
                compilerOptions { this.jvmTarget.set(jvmTarget) }
            }
            jvm("desktop")

            // Both targets are JVM, so the common suite runs unchanged under
            // desktopTest with no Android SDK or emulator needed.
            sourceSets.apply {
                commonTest.dependencies {
                    implementation(lib("kotlin-test"))
                    implementation(lib("kotlinx-coroutines-test"))
                }
            }
        }

        extensions.configure<LibraryExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig { minSdk = libs.intVersion("minSdk") }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTarget.target)
                targetCompatibility = JavaVersion.toVersion(jvmTarget.target)
            }
        }
    }
}
