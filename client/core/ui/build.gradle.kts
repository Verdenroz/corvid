plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
}

android { namespace = "com.verdenroz.corvid.core.ui" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.designsystem)
            api(projects.core.text)
            api(projects.core.model)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }
}
