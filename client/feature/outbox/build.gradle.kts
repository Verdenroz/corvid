plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.outbox" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
