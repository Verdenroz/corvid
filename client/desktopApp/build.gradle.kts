import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("corvid.compose.multiplatform")
}

val jvmTargetVersion = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

java {
    sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion.target)
    targetCompatibility = JavaVersion.toVersion(jvmTargetVersion.target)
}

kotlin {
    compilerOptions {
        jvmTarget.set(jvmTargetVersion)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.common)
    implementation(projects.core.auth)
    implementation(projects.core.discovery)
    implementation(projects.core.prefs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.verdenroz.corvid.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "corvid"
            packageVersion = appVersionName()
            modules("java.instrument", "java.management", "java.naming", "jdk.unsupported")
            linux { iconFile.set(project.file("src/main/resources/corvid-512.png")) }
            windows { iconFile.set(project.file("src/main/resources/corvid.ico")) }
            macOS {
                // Dmg refuses MAJOR=0 versions; placeholder until 1.0.
                packageVersion = "1.0.0"
                iconFile.set(project.file("src/main/resources/corvid.icns"))
            }
        }
    }
}
