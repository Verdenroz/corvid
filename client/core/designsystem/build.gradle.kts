plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
}

android { namespace = "com.verdenroz.corvid.core.designsystem" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }
    }
}
