package com.verdenroz.corvid.app

import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.digest_banner
import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/** The latest digest this device has not seen: its [id] and the [count] of messages since [since]. */
data class DigestBanner(
    val id: String,
    val count: Int,
    val since: Instant,
) {
    val line: UiText
        get() =
            UiText.Plural(
                Res.plurals.digest_banner,
                count,
                listOf(count, stamp(since, TimeZone.currentSystemDefault(), Clock.System.now())),
            )
}

/**
 * Offers the server's latest digest while the app is unlocked, until it is opened or dismissed; the id of the last
 * digest dealt with is remembered across runs. A digest with no mail is never offered.
 */
class NewDigest(
    private val repository: DigestRepository,
    private val preferences: DevicePreferences,
) {
    private val _banner = MutableStateFlow<DigestBanner?>(null)
    val banner: StateFlow<DigestBanner?> = _banner

    private val opened = mutableSetOf<String?>()

    /** Checks on entry and again on each emission of [triggers]; ends when the caller's scope does. */
    suspend fun run(triggers: Flow<Unit>) {
        check()
        triggers.collect { check() }
    }

    suspend fun dismiss() {
        _banner.value?.let { seen(it.id) }
    }

    /** Called when the digest [id] is on screen, where null is the latest. */
    suspend fun opened(id: String?) {
        opened += id
        val shown = _banner.value ?: return
        if (id == null || id == shown.id) seen(shown.id)
    }

    private suspend fun seen(id: String) {
        _banner.value = null
        preferences.setDigestSeen(id)
    }

    private suspend fun check() {
        val digest =
            try {
                repository.latest()
            } catch (e: JmapException) {
                null
            } ?: return
        val count = digest.groups.sumOf { it.items.size }
        if (count == 0 || digest.id == preferences.settings.value.digestSeen) return
        if (null in opened || digest.id in opened) return seen(digest.id)
        _banner.value = DigestBanner(digest.id, count, digest.from)
    }
}
