plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.keyring" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.jmap)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        val desktopTest by getting {
            dependencies {
                // Only DeviceKeyRepositoryLiveTest needs these: basic auth against the
                // throwaway rig, which has no OIDC issuer to mint a bearer token from.
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
    }
}
