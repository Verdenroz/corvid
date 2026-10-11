plugins {
    id("corvid.android.application")
    id("corvid.compose.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.verdenroz.corvid"
    defaultConfig {
        applicationId = "com.verdenroz.corvid"
        // Consumed by oidc-appsupport's own manifest to install the redirect intent filter.
        addManifestPlaceholders(mapOf("oidcRedirectScheme" to "com.verdenroz.corvid"))
    }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.common)
    implementation(projects.core.auth)
    implementation(projects.core.data)
    implementation(projects.core.discovery)
    implementation(projects.core.model)
    implementation(projects.core.prefs)
    implementation(projects.core.keyring)
    implementation(projects.feature.settings)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // The connector depends on JVM tink, whose classes collide with tink-android from security-crypto.
    implementation(libs.unifiedpush.connector) { exclude(group = "com.google.crypto.tink", module = "tink") }
    implementation(libs.unifiedpush.embedded.fcm)
    implementation(libs.tink.android)
    testImplementation(libs.kotlin.test)
}
