plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.app" }

val generateBuildInfo by tasks.registering {
    val version = providers.gradleProperty("corvid.version").getOrElse("0.1.0")
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/verdenroz/corvid/app/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.verdenroz.corvid.app\n\ninternal const val APP_VERSION = \"$version\"\n")
    }
}

kotlin {
    sourceSets {
        commonMain { kotlin.srcDir(generateBuildInfo.map { it.outputs.files }) }
        commonMain.dependencies {
            api(projects.core.designsystem)
            api(projects.core.ui)
            implementation(projects.core.common)
            implementation(projects.core.model)
            implementation(projects.core.jmap)
            implementation(projects.core.auth)
            implementation(projects.core.discovery)
            implementation(projects.core.domain)
            implementation(projects.core.keyring)
            implementation(projects.core.prefs)
            implementation(projects.core.data)
            implementation(projects.core.text)
            implementation(libs.compose.components.resources)
            implementation(projects.feature.signin)
            implementation(projects.feature.mailbox)
            implementation(projects.feature.compose)
            implementation(projects.feature.filters)
            implementation(projects.feature.settings)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.navigationevent.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.zxing.core)
        }
        desktopMain.dependencies {
            implementation(libs.zxing.core)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}
