plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
}

android { namespace = "com.verdenroz.corvid.core.text" }

kotlin {
    sourceSets {
        val desktopTest by getting {
            dependencies {
                implementation(projects.core.testing)
            }
        }
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.components.resources)
        }
    }
}

compose.resources {
    publicResClass = true
}
