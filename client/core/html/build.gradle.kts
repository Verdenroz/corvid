plugins {
    id("corvid.kmp.library")
}

android { namespace = "com.verdenroz.corvid.core.html" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ksoup)
        }
    }
}
