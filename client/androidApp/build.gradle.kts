plugins {
    id("corvid.android.application")
    id("corvid.compose.multiplatform")
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.compose.material3)
}
