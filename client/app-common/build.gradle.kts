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
            implementation(projects.core.model)
            implementation(projects.core.jmap)
            implementation(projects.core.auth)
            implementation(projects.core.domain)
            implementation(projects.core.keyring)
            implementation(projects.core.data)
            implementation(projects.core.text)
            implementation(libs.compose.components.resources)
            implementation(projects.feature.signin)
            implementation(projects.feature.mailbox)
            implementation(projects.feature.compose)
            implementation(projects.feature.filters)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
