package com.verdenroz.corvid.app

import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.app_update_cannot_install
import com.verdenroz.corvid.appcommon.resources.app_update_corrupt
import com.verdenroz.corvid.appcommon.resources.app_update_no_build
import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.AppReleaseRepository
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.data.DownloadFailure
import com.verdenroz.corvid.core.data.DownloadResult
import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.AppRelease
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Knows whether the server carries a newer app than [installedCode] while the app is unlocked, and can fetch,
 * verify and hand that build to the platform's [AppInstaller]. Nothing is persisted: a dismissal lasts the session.
 */
class AppUpdates(
    private val repository: AppReleaseRepository,
    private val installer: AppInstaller,
    private val directory: File,
    private val installedCode: Int = APP_VERSION_CODE,
    private val checkEvery: Duration = CHECK_EVERY,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.UpToDate)
    val state: StateFlow<AppUpdateState> = _state

    private val _dismissed = MutableStateFlow<Int?>(null)

    /** The version code whose banner was dismissed; a newer release shows its banner again. */
    val dismissed: StateFlow<Int?> = _dismissed

    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun dismiss() {
        _state.value.release()?.let { _dismissed.value = it.versionCode }
    }

    fun update() {
        requests.trySend(Unit)
    }

    /**
     * Checks on entry, again as soon as [foreground] has emitted or [checkEvery] has passed since the last check began,
     * and installs when [update] asks; ends when the caller's scope does. Each [foreground] emission also offers the
     * update again if the user has since allowed installs.
     */
    suspend fun run(foreground: Flow<Unit>) {
        coroutineScope {
            val due = Channel<Unit>(Channel.CONFLATED)
            launch {
                foreground.collect {
                    recheckPermission()
                    due.trySend(Unit)
                }
            }
            launch {
                while (true) {
                    delay(checkEvery)
                    due.trySend(Unit)
                }
            }
            launch { for (request in requests) install() }
            while (true) {
                check()
                due.receive()
            }
        }
    }

    private suspend fun check() {
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Ready) return
        val release =
            try {
                repository.latest()
            } catch (e: JmapException) {
                return
            }
        _state.value = if (release != null && offers(release)) AppUpdateState.Available(release) else AppUpdateState.UpToDate
    }

    private fun offers(release: AppRelease): Boolean {
        val platform = installer.platform ?: return false
        return release.versionCode > installedCode && release.artifact(platform) != null
    }

    private fun recheckPermission() {
        val current = _state.value
        if (current is AppUpdateState.NeedsPermission && installer.canInstall()) _state.value = AppUpdateState.Available(current.release)
    }

    private suspend fun install() {
        val platform = installer.platform ?: return
        val release = _state.value.release() ?: return
        val artifact = release.artifact(platform) ?: return
        if (_state.value is AppUpdateState.NeedsPermission) {
            if (!installer.canInstall()) {
                installer.allowInstalls()
                return
            }
            _state.value = AppUpdateState.Downloading(release, 1f)
            _state.value = handOff(release, File(directory, artifact.path))
            return
        }
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Ready) return
        _state.value = AppUpdateState.Downloading(release, 0f)
        val result =
            try {
                withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    repository.download(release, platform, File(directory, artifact.path)) { received ->
                        _state.value = AppUpdateState.Downloading(release, received.toFloat() / artifact.size)
                    }
                }
            } catch (e: JmapException) {
                _state.value = AppUpdateState.Failed(release, e.userMessage())
                return
            }
        _state.value =
            when (result) {
                is DownloadResult.Failed -> AppUpdateState.Failed(release, uiText(result.reason.message()))
                is DownloadResult.Verified -> handOff(release, result.file)
            }
    }

    private suspend fun handOff(
        release: AppRelease,
        file: File,
    ): AppUpdateState =
        when (val result = installer.install(file)) {
            InstallResult.Started -> AppUpdateState.Ready(release)
            InstallResult.NotSupported -> AppUpdateState.Failed(release, uiText(Res.string.app_update_cannot_install))
            InstallResult.Cancelled -> AppUpdateState.Available(release)
            InstallResult.NeedsPermission -> AppUpdateState.NeedsPermission(release)
            is InstallResult.Failed -> AppUpdateState.Failed(release, UiText.FromServer(result.message))
        }
}

private fun AppUpdateState.release(): AppRelease? =
    when (this) {
        is AppUpdateState.Available -> release
        is AppUpdateState.Downloading -> release
        is AppUpdateState.Ready -> release
        is AppUpdateState.NeedsPermission -> release
        is AppUpdateState.Failed -> release
        AppUpdateState.UpToDate -> null
    }

private fun DownloadFailure.message() =
    when (this) {
        DownloadFailure.NoBuild -> Res.string.app_update_no_build
        DownloadFailure.WrongSize, DownloadFailure.WrongDigest -> Res.string.app_update_corrupt
    }

private val CHECK_EVERY = 24.hours
