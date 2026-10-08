plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.settings" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(projects.core.jmap)
            implementation(projects.core.keyring)
            implementation(projects.core.prefs)
        }
        androidMain.dependencies {
            // The bundled ML Kit model needs no Play Services.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.mlkit.vision)
            implementation(libs.mlkit.barcode.scanning)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
