plugins {
    id("corvid.kmp.library")
    id("corvid.compose.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.app" }

val generateBuildInfo by tasks.registering {
    val version = appVersionName()
    val versionCode = appVersionCode()
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", version)
    inputs.property("versionCode", versionCode)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/verdenroz/corvid/app/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.verdenroz.corvid.app\n\n" +
                "internal const val APP_VERSION = \"$version\"\n" +
                "internal const val APP_VERSION_CODE = $versionCode\n",
        )
    }
}

kotlin {
    sourceSets {
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)

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
            implementation(projects.feature.digest)
            implementation(projects.feature.settings)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
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
        jvmShared.dependencies {
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
