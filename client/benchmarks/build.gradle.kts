import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

allOpen { annotation("org.openjdk.jmh.annotations.State") }

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

kotlin { compilerOptions { jvmTarget.set(javaTarget) } }

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
    targetCompatibility = JavaVersion.toVersion(javaTarget.target)
}

dependencies {
    implementation(projects.core.html)
    implementation(libs.kotlinx.benchmark.runtime)
}

benchmark {
    targets { register("main") }
    configurations {
        named("main") {
            warmups = 5
            iterations = 8
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
        }
    }
}
