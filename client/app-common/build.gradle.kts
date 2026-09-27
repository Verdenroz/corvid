plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
}

android { namespace = "com.verdenroz.corvid.app" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.designsystem)
            api(projects.core.ui)
            implementation(projects.core.common)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }
}
