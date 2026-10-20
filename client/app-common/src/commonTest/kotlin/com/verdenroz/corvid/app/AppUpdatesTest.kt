package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.common.sha256Hex
import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.AppReleaseRepository
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.model.AppPlatform
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.KeyedTextResolver
import com.verdenroz.corvid.core.testing.client
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val APK = ByteArray(1_000) { it.toByte() }

private class RecordingInstaller(
    override val platform: AppPlatform?,
    var result: InstallResult = InstallResult.Started,
    private val verdict: CompletableDeferred<Unit>? = null,
) : AppInstaller {
    val installed = mutableListOf<ByteArray>()
    var allowRequests = 0
    var allowed = true

    override suspend fun install(file: File): InstallResult {
        installed += file.readBytes()
        verdict?.await()
        return result
    }

    override fun canInstall() = allowed

    override fun allowInstalls() {
        allowRequests++
    }
}

private class UpdatesRig(
    val fake: FakeJmapServer,
    val updates: AppUpdates,
    val installer: RecordingInstaller,
    val foreground: Channel<Unit>,
    val directory: File,
)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.updatesRig(
    fake: FakeJmapServer,
    installer: RecordingInstaller = RecordingInstaller(AppPlatform.Android),
    installed: Int = 5,
    checkEvery: Duration = Duration.INFINITE,
    token: String? = null,
): UpdatesRig {
    val directory = createTempDirectory("corvid-update").toFile().apply { deleteOnExit() }
    val foreground = Channel<Unit>(Channel.CONFLATED)
    val updates = AppUpdates(AppReleaseRepository(fake.client(token)), installer, directory, installed, checkEvery)
    backgroundScope.launch { updates.run(foreground.receiveAsFlow()) }
    runCurrent()
    return UpdatesRig(fake, updates, installer, foreground, directory)
}

/** The fake answers on another thread, so the test waits on real time for what it caused. */
private suspend fun eventually(condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { while (!condition()) delay(5) }
    }
}

private fun FakeJmapServer.manifestReads() = app.requests.count { it == "manifest.json" }

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    @Test
    fun `a server without a release offers nothing`() =
        runTest {
            val rig = updatesRig(FakeJmapServer())

            eventually { rig.fake.manifestReads() == 1 }

            assertEquals(AppUpdateState.UpToDate, rig.updates.state.value)
        }

    @Test
    fun `a release no newer than the installed build offers nothing`() =
        runTest {
            val equal = updatesRig(FakeJmapServer().apply { app.release(5, android = APK) }, installed = 5)
            val older = updatesRig(FakeJmapServer().apply { app.release(4, android = APK) }, installed = 5)

            eventually { equal.fake.manifestReads() == 1 && older.fake.manifestReads() == 1 }

            assertEquals(AppUpdateState.UpToDate, equal.updates.state.value)
            assertEquals(AppUpdateState.UpToDate, older.updates.state.value)
        }

    @Test
    fun `a newer release is offered`() =
        runTest {
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, android = APK) })

            eventually { rig.updates.state.value is AppUpdateState.Available }

            val offer = rig.updates.state.value as AppUpdateState.Available
            assertEquals("0.1.6", offer.release.versionName)
        }

    @Test
    fun `a release without a build for this platform is not offered`() =
        runTest {
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, linux = APK) })

            eventually { rig.fake.manifestReads() == 1 }

            assertEquals(AppUpdateState.UpToDate, rig.updates.state.value)
        }

    @Test
    fun `a platform that cannot be updated is never offered a release`() =
        runTest {
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, android = APK, linux = APK) }, RecordingInstaller(null))

            eventually { rig.fake.manifestReads() == 1 }

            assertEquals(AppUpdateState.UpToDate, rig.updates.state.value)
        }

    @Test
    fun `updating downloads the verified build and hands it to the installer`() =
        runTest {
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, android = APK) })
            eventually { rig.updates.state.value is AppUpdateState.Available }

            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.Ready }

            assertEquals(1, rig.installer.installed.size)
            assertEquals(APK.toList(), rig.installer.installed.single().toList())
        }

    @Test
    fun `an install the platform fails shows its message`() =
        runTest {
            val rig =
                updatesRig(
                    FakeJmapServer().apply { app.release(6, android = APK) },
                    RecordingInstaller(AppPlatform.Android, InstallResult.Failed("Package conflicts")),
                )
            eventually { rig.updates.state.value is AppUpdateState.Available }

            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.Failed }

            val failed = rig.updates.state.value as AppUpdateState.Failed
            assertEquals("Package conflicts", KeyedTextResolver.resolve(failed.message))
        }

    @Test
    fun `an install needing permission asks for it on the next update instead of downloading again`() =
        runTest {
            val rig =
                updatesRig(
                    FakeJmapServer().apply { app.release(6, android = APK) },
                    RecordingInstaller(AppPlatform.Android, InstallResult.NeedsPermission),
                )
            eventually { rig.updates.state.value is AppUpdateState.Available }
            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.NeedsPermission }
            rig.installer.allowed = false

            rig.updates.update()
            eventually { rig.installer.allowRequests == 1 }

            assertEquals(1, rig.installer.installed.size)
        }

    @Test
    fun `updating once installs are allowed installs the downloaded build without downloading again`() =
        runTest {
            val rig =
                updatesRig(
                    FakeJmapServer().apply { app.release(6, android = APK) },
                    RecordingInstaller(AppPlatform.Android, InstallResult.NeedsPermission),
                )
            eventually { rig.updates.state.value is AppUpdateState.Available }
            rig.installer.allowed = false
            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.NeedsPermission }
            rig.installer.allowed = true
            rig.installer.result = InstallResult.Started

            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.Ready }

            assertEquals(2, rig.installer.installed.size)
            assertEquals(0, rig.installer.allowRequests)
            assertEquals(1, rig.fake.app.requests.count { it != "manifest.json" })
        }

    @Test
    fun `returning to the foreground after allowing installs offers the update again`() =
        runTest {
            val rig =
                updatesRig(
                    FakeJmapServer().apply { app.release(6, android = APK) },
                    RecordingInstaller(AppPlatform.Android, InstallResult.NeedsPermission),
                )
            eventually { rig.updates.state.value is AppUpdateState.Available }
            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.NeedsPermission }

            rig.foreground.send(Unit)

            eventually { rig.updates.state.value is AppUpdateState.Available }
        }

    @Test
    fun `a download whose digest is wrong fails, installs nothing and leaves no file`() =
        runTest {
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, android = APK, sha256 = { sha256Hex(it.reversedArray()) }) })
            eventually { rig.updates.state.value is AppUpdateState.Available }

            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.Failed }

            val failed = rig.updates.state.value as AppUpdateState.Failed
            assertEquals("app_update_corrupt", KeyedTextResolver.resolve(failed.message))
            assertEquals(emptyList(), rig.installer.installed)
            assertEquals(emptyList(), rig.directory.list()?.toList())
        }

    @Test
    fun `the bearer token rides on the check and the download`() =
        runTest {
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, android = APK) }, token = "tok")
            eventually { rig.updates.state.value is AppUpdateState.Available }

            rig.updates.update()
            eventually { rig.updates.state.value is AppUpdateState.Ready }

            assertEquals(listOf<String?>("Bearer tok", "Bearer tok"), rig.fake.app.authorizations)
        }

    @Test
    fun `the server is asked again after a day`() =
        runTest {
            val fake = FakeJmapServer(StandardTestDispatcher(testScheduler))
            val rig = updatesRig(fake, checkEvery = 24.hours)
            assertEquals(1, fake.manifestReads())

            advanceTimeBy(23.hours)
            runCurrent()
            assertEquals(1, fake.manifestReads())
            fake.app.release(6, android = APK)
            advanceTimeBy(1.hours + 1.seconds)
            runCurrent()

            assertEquals(2, fake.manifestReads())
            assertIs<AppUpdateState.Available>(rig.updates.state.value)
        }

    @Test
    fun `returning to the foreground checks at once`() =
        runTest {
            val rig = updatesRig(FakeJmapServer(), checkEvery = Duration.INFINITE)
            eventually { rig.fake.manifestReads() == 1 }

            rig.foreground.send(Unit)
            eventually { rig.fake.manifestReads() == 2 }
        }

    @Test
    fun `a dismissed offer is remembered by version, so a newer one shows again`() =
        runTest {
            val fake = FakeJmapServer(StandardTestDispatcher(testScheduler)).apply { app.release(6, android = APK) }
            val rig = updatesRig(fake, checkEvery = 24.hours)
            assertIs<AppUpdateState.Available>(rig.updates.state.value)

            rig.updates.dismiss()
            assertNull(rig.updates.banner.first())
            fake.app.release(7, android = APK)
            advanceTimeBy(25.hours)
            runCurrent()

            assertEquals(7, (rig.updates.banner.first() as AppUpdateState.Available).release.versionCode)
            assertEquals(7, (rig.updates.state.value as AppUpdateState.Available).release.versionCode)
        }

    @Test
    fun `the banner stays up while the build downloads and the installer decides`() =
        runTest {
            val verdict = CompletableDeferred<Unit>()
            val installer = RecordingInstaller(AppPlatform.Android, InstallResult.Failed("Not installed"), verdict)
            val rig = updatesRig(FakeJmapServer().apply { app.release(6, android = APK) }, installer)
            eventually { rig.updates.state.value is AppUpdateState.Available }

            rig.updates.update()
            eventually { rig.installer.installed.isNotEmpty() }

            assertEquals(1f, (rig.updates.banner.first() as AppUpdateState.Downloading).progress)
            verdict.complete(Unit)
            eventually { rig.updates.state.value is AppUpdateState.Failed }
            assertIs<AppUpdateState.Failed>(rig.updates.banner.first())
        }
}
