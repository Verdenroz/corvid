plugins {
    id("corvid.android.application")
    id("corvid.compose.multiplatform")
}

android {
    namespace = "com.verdenroz.corvid"
    defaultConfig { applicationId = "com.verdenroz.corvid" }
}

dependencies {
    implementation(projects.appCommon)
    implementation(projects.core.common)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.compose.material3)
}
