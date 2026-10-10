package com.verdenroz.corvid.core.model

enum class AppPlatform { Android, Linux }

/** One downloadable build: its [path] under `/corvid/app/`, the lowercase hex [sha256] and the [size] in bytes. */
data class AppArtifact(
    val path: String,
    val sha256: String,
    val size: Long,
)

/** The newest app build the user's own server carries; a platform it has no build for is null. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val android: AppArtifact?,
    val linux: AppArtifact?,
) {
    fun artifact(platform: AppPlatform): AppArtifact? =
        when (platform) {
            AppPlatform.Android -> android
            AppPlatform.Linux -> linux
        }
}
