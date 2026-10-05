package com.verdenroz.corvid.app

import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.notices_more
import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Notice
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.text.SystemTextResolver
import com.verdenroz.corvid.core.text.TextResolver
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The notices this session has not had dismissed: the [newest] of [count]. */
data class NoticeBanner(
    val newest: Notice,
    val count: Int,
) {
    val line: UiText get() = if (count == 1) UiText.FromServer(newest.title) else uiText(Res.string.notices_more, newest.title, count - 1)
}

/** Shows a system notification; each platform app provides one. Carries server text only, never mail. */
fun interface NoticeNotifier {
    fun post(
        title: String,
        text: String,
    )
}

/**
 * Follows the server's notices while the app is unlocked. Each notice newer than the last one this device
 * was shown becomes a banner and one system notification, and the last id is remembered across runs.
 */
class Notices(
    private val repository: NoticeRepository,
    private val preferences: DevicePreferences,
    private val notifier: NoticeNotifier,
    private val text: TextResolver = SystemTextResolver,
    private val pollEvery: Duration = POLL_EVERY,
) {
    private val _banner = MutableStateFlow<NoticeBanner?>(null)
    val banner: StateFlow<NoticeBanner?> = _banner

    private val polling = Mutex()
    private var seen = preferences.settings.value.noticesSeen

    fun dismiss() {
        _banner.value = null
    }

    /** Polls on entry, whenever [foreground] emits, and every [pollEvery]; ends when the caller's scope does. */
    suspend fun run(foreground: Flow<Unit>) {
        coroutineScope {
            launch { foreground.collect { poll() } }
            while (true) {
                poll()
                delay(pollEvery)
            }
        }
    }

    private suspend fun poll() {
        polling.withLock {
            val fresh =
                try {
                    repository.after(seen)
                } catch (e: JmapException) {
                    return@withLock
                }
            if (fresh.isNullOrEmpty()) return@withLock
            seen = fresh.last().id
            preferences.setNoticesSeen(seen)
            val banner = NoticeBanner(fresh.last(), (_banner.value?.count ?: 0) + fresh.size)
            _banner.value = banner
            notifier.post(text.resolve(banner.line), banner.newest.detail)
        }
    }
}

private val POLL_EVERY = 60.seconds
