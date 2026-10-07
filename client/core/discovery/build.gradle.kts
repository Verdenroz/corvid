plugins {
    id("corvid.kmp.library")
}

android { namespace = "com.verdenroz.corvid.core.discovery" }

kotlin {
    sourceSets {
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)

        commonMain.dependencies {
            api(projects.core.domain)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
        }
        jvmShared.dependencies {
            implementation(libs.dnsjava)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
