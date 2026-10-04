plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.compose" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(libs.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
