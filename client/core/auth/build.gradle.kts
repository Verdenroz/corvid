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

// Fido2LiveTest reads this; a run with a key attached must not be served to a run without one.
tasks.withType<Test>().configureEach {
    inputs.property("fido2Live", providers.environmentVariable("CORVID_FIDO2_LIVE").orElse(""))
}
