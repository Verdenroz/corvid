plugins {
    id("corvid.kmp.feature")
}

android { namespace = "com.verdenroz.corvid.feature.settings" }

kotlin {
    sourceSets {
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)

        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(projects.core.jmap)
            implementation(projects.core.keyring)
            implementation(projects.core.prefs)
        }
        jvmShared.dependencies {
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
