plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.data" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.kotlinx.datetime)
            api(projects.core.text)
            implementation(projects.core.auth)
            implementation(projects.core.common)
            implementation(projects.core.html)
            implementation(projects.core.jmap)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        val desktopTest by getting {
            dependencies {
                // Resolving a string resource on the desktop JVM asks Skiko for the system theme.
                implementation(org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs)
            }
        }
    }
}

// AliasReplyLiveTest reads this, so Gradle never reuses a rig run's result for a run without a rig.
tasks.withType<Test>().configureEach {
    inputs.property("aliasLive", providers.environmentVariable("CORVID_ALIAS_LIVE").orElse(""))
}
