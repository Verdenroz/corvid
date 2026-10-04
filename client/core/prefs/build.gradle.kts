plugins {
    id("corvid.kmp.library")
}

android { namespace = "com.verdenroz.corvid.core.prefs" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.datastore.preferences.core)
            api(libs.kotlinx.coroutines.core)
            implementation(projects.core.common)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
    }
}
