plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.testing" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.jmap)
            api(projects.core.text)
            implementation(projects.core.common)
            api(libs.ktor.client.mock)
            api(libs.kotlinx.coroutines.test)
            api(libs.jetbrains.lifecycle.viewmodel)
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        desktopMain.dependencies {
            api(libs.compose.components.resources)
            implementation(libs.compose.ui.test)
            implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
        }
    }
}
