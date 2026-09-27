plugins {
    id("corvid.kmp.library")
}

android { namespace = "com.verdenroz.corvid.core.common" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
        }
    }
}
