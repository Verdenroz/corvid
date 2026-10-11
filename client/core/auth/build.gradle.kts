plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.auth" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.jmap)
            api(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
