package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.common.sha256Hex
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.AppPlatform
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

private val APK = ByteArray(200_000) { (it % 251).toByte() }
private val TARBALL = "linux build".encodeToByteArray()

private suspend fun repository(
    fake: FakeJmapServer,
    token: String? = null,
) = AppReleaseRepository(fake.client(token))

private fun workDir(): File = createTempDirectory("corvid-app").toFile().apply { deleteOnExit() }

private fun File.siblings(): List<String> = parentFile!!.list()!!.toList()

class AppReleaseRepositoryTest {
    @Test
    fun `a server without a release offers none`() =
        runTest {
            assertNull(repository(FakeJmapServer()).latest())
        }

    @Test
    fun `a manifest is read field for field`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK, linux = TARBALL) }

            val release = repository(fake).latest()

            assertEquals(7, release?.versionCode)
            assertEquals("0.1.7", release?.versionName)
            assertEquals(APK.size.toLong(), release?.android?.size)
            assertEquals(sha256Hex(APK), release?.android?.sha256)
            assertEquals("corvid-linux-x64.tar.gz", release?.linux?.path)
        }

    @Test
    fun `a platform the manifest omits has no build`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }

            assertNull(repository(fake).latest()?.linux)
        }

    @Test
    fun `a manifest of another version is refused`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK, version = 2) }

            val failure = assertFailsWith<JmapException> { repository(fake).latest() }

            assertIs<JmapError.Request>(failure.error)
        }

    @Test
    fun `a verified build lands at the destination and nowhere else`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake)
            val dest = File(workDir(), "corvid.apk")

            val result = repository.download(repository.latest()!!, AppPlatform.Android, dest)

            assertEquals(DownloadResult.Verified(dest), result)
            assertContentEquals(APK, dest.readBytes())
            assertEquals(listOf("corvid.apk"), dest.siblings())
        }

    @Test
    fun `a destination that already verifies is reused without a request`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake)
            val release = repository.latest()!!
            val dest = File(workDir(), "corvid.apk")
            repository.download(release, AppPlatform.Android, dest)

            val result = repository.download(release, AppPlatform.Android, dest)

            assertEquals(DownloadResult.Verified(dest), result)
            assertEquals(1, fake.app.requests.count { it != "manifest.json" })
        }

    @Test
    fun `a destination that no longer verifies is fetched again`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake)
            val release = repository.latest()!!
            val dest = File(workDir(), "corvid.apk").apply { writeBytes(APK.reversedArray()) }

            val result = repository.download(release, AppPlatform.Android, dest)

            assertEquals(DownloadResult.Verified(dest), result)
            assertContentEquals(APK, dest.readBytes())
        }

    @Test
    fun `progress reports the bytes received up to the whole file`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake)
            val seen = mutableListOf<Long>()

            repository.download(repository.latest()!!, AppPlatform.Android, File(workDir(), "corvid.apk"), seen::add)

            assertEquals(APK.size.toLong(), seen.last())
        }

    @Test
    fun `a digest that does not match fails and leaves no file`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK, sha256 = { sha256Hex(it.reversedArray()) }) }
            val repository = repository(fake)
            val dest = File(workDir(), "corvid.apk")

            val result = repository.download(repository.latest()!!, AppPlatform.Android, dest)

            assertEquals(DownloadResult.Failed(DownloadFailure.WrongDigest), result)
            assertFalse(dest.exists())
            assertEquals(emptyList(), dest.siblings())
        }

    @Test
    fun `a size that does not match fails and leaves no file`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK, size = { it.size + 1L }) }
            val repository = repository(fake)
            val dest = File(workDir(), "corvid.apk")

            val result = repository.download(repository.latest()!!, AppPlatform.Android, dest)

            assertEquals(DownloadResult.Failed(DownloadFailure.WrongSize), result)
            assertEquals(emptyList(), dest.siblings())
        }

    @Test
    fun `a platform without a build fails before any request`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake)
            val release = repository.latest()!!
            val requests = fake.app.requests.size

            val result = repository.download(release, AppPlatform.Linux, File(workDir(), "corvid.tar.gz"))

            assertEquals(DownloadResult.Failed(DownloadFailure.NoBuild), result)
            assertEquals(requests, fake.app.requests.size)
        }

    @Test
    fun `a build the server no longer has is a request failure and leaves no file`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake)
            val release = repository.latest()!!
            val dest = File(workDir(), "corvid.apk")
            fake.app.unpublish("corvid.apk")

            assertFailsWith<JmapException> { repository.download(release, AppPlatform.Android, dest) }

            assertEquals(emptyList(), dest.siblings())
        }

    @Test
    fun `the bearer token rides on the manifest and the download`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake, token = "tok")

            repository.download(repository.latest()!!, AppPlatform.Android, File(workDir(), "corvid.apk"))

            assertEquals(listOf<String?>("Bearer tok", "Bearer tok"), fake.app.authorizations)
        }

    @Test
    fun `a rejected token trips the latch for the download as for any request`() =
        runTest {
            val fake = FakeJmapServer().apply { app.release(7, android = APK) }
            val repository = repository(fake, token = "tok")
            val release = repository.latest()!!
            fake.app.refuse = true

            val dest = File(workDir(), "corvid.apk")

            val download = assertFailsWith<JmapException> { repository.download(release, AppPlatform.Android, dest) }
            val later = assertFailsWith<JmapException> { repository.latest() }

            assertEquals(JmapError.Unauthorized, download.error)
            assertEquals(JmapError.Unauthorized, later.error)
        }
}
