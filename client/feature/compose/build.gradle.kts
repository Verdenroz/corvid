plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.compose" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.compose.ui.test)
            }
        }
    }
}
