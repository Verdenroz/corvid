import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("corvid.compose.multiplatform")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.common)
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
            packageVersion = providers.gradleProperty("corvid.version").getOrElse("0.1.0")
            // Dmg refuses MAJOR=0 versions; placeholder until 1.0.
            macOS { packageVersion = "1.0.0" }
        }
    }
}
