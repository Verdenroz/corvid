plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
}

android { namespace = "com.verdenroz.corvid.core.ui" }

kotlin {
    sourceSets {
        val desktopTest by getting {
            dependencies {
                implementation(projects.core.testing)
                implementation(libs.compose.ui.test)
            }
        }
        commonMain.dependencies {
            api(projects.core.designsystem)
            api(projects.core.text)
            api(projects.core.model)
            implementation(projects.core.data)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }
}
