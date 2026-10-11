plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.auth" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.jmap)
            api(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        // The Linux and macOS desktop reach a security key through the system's libfido2.
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
            }
        }
    }
}
