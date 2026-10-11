plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.signin" }

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        commonMain.dependencies {
            implementation(projects.core.prefs)
            implementation(libs.kalinjul.oidc.core)
        }
    }
}
