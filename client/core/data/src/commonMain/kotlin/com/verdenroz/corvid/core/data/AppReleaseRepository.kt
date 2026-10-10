package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.common.toHex
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.AppArtifact
import com.verdenroz.corvid.core.model.AppPlatform
import com.verdenroz.corvid.core.model.AppRelease
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val APP_PATH = "/corvid/app"
private const val MANIFEST_VERSION = 1

@Serializable
private class WireManifest(
    val version: Int,
    val versionCode: Int,
    val versionName: String,
    val android: WireArtifact? = null,
    val linux: WireArtifact? = null,
)

@Serializable
private class WireArtifact(
    val path: String,
    val sha256: String,
    val size: Long,
)

sealed interface DownloadResult {
    data class Verified(val file: File) : DownloadResult

    data class Failed(val reason: DownloadFailure) : DownloadResult
}

enum class DownloadFailure { NoBuild, WrongSize, WrongDigest }

/** The app builds the server carries, behind `/corvid/app` on the web tier. */
class AppReleaseRepository(private val jmapClient: JmapClient) {
    /** Null when the server carries no release. */
    suspend fun latest(): AppRelease? {
        val body = jmapClient.webGet("$APP_PATH/manifest.json") ?: return null
        return body.decodeWeb(WireManifest.serializer(), "AppManifest") { it.toRelease() }
    }

    /** Fetches [platform]'s build to [dest], which only ever receives a file whose size and SHA-256 match the manifest. */
    suspend fun download(
        release: AppRelease,
        platform: AppPlatform,
        dest: File,
        onProgress: (Long) -> Unit = {},
    ): DownloadResult {
        val artifact = release.artifact(platform) ?: return DownloadResult.Failed(DownloadFailure.NoBuild)
        val partial = File(dest.parentFile, "${dest.name}.part")
        try {
            partial.outputStream().use { jmapClient.webDownload("$APP_PATH/${artifact.path}", it, onProgress) }
            partial.failureAgainst(artifact)?.let { return DownloadResult.Failed(it) }
            Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return DownloadResult.Verified(dest)
        } finally {
            partial.delete()
        }
    }
}

private fun File.failureAgainst(artifact: AppArtifact): DownloadFailure? =
    when {
        length() != artifact.size -> DownloadFailure.WrongSize
        sha256() != artifact.sha256.lowercase() -> DownloadFailure.WrongDigest
        else -> null
    }

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun WireManifest.toRelease(): AppRelease {
    require(version == MANIFEST_VERSION) { "manifest version $version" }
    return AppRelease(versionCode, versionName, android?.toArtifact(), linux?.toArtifact())
}

private fun WireArtifact.toArtifact() = AppArtifact(path, sha256, size)
