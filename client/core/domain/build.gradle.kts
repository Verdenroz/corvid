plugins {
    id("corvid.kmp.library")
}

android { namespace = "com.verdenroz.corvid.core.domain" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.auth)
            implementation(libs.koin.core)
        }
    }
}
