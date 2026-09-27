plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.auth" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.jmap)
            api(libs.kalinjul.oidc.core)
            api(libs.kalinjul.oidc.appsupport)
            api(libs.kalinjul.oidc.preferences)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
