plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.testing" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.jmap)
            api(libs.ktor.client.mock)
            api(libs.kotlinx.coroutines.test)
            api(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
