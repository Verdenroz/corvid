plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.signin" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kalinjul.oidc.core)
        }
    }
}
