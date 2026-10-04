plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.settings" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(projects.core.jmap)
            implementation(projects.core.prefs)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
