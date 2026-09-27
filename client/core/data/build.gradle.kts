plugins {
    id("corvid.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.verdenroz.corvid.core.data" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.jmap)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.jsoup)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jsoup)
            }
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
