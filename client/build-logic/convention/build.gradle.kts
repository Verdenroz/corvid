import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.verdenroz.corvid.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.android.tools.common)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "corvid.kmp.library"
            implementationClass = "KotlinMultiplatformLibraryConventionPlugin"
        }
        register("kmpFeature") {
            id = "corvid.kmp.feature"
            implementationClass = "KotlinMultiplatformFeatureConventionPlugin"
        }
        register("composeMultiplatform") {
            id = "corvid.compose.multiplatform"
            implementationClass = "ComposeMultiplatformConventionPlugin"
        }
        register("androidApplication") {
            id = "corvid.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
