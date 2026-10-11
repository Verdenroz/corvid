plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.mailbox" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(projects.core.html)
            implementation(libs.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}
