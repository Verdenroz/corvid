package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.DigestRepository
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.Digest
import com.verdenroz.corvid.core.model.items
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.feature.mailbox.OvernightEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Offers the server's latest digest while the app is unlocked, until it is opened or dismissed; the id of the last
 * digest dealt with is remembered across runs. A digest with no mail is never offered.
 */
class NewDigest(
    private val repository: DigestRepository,
    private val preferences: DevicePreferences,
) {
    private val _entry = MutableStateFlow<OvernightEntry?>(null)
    val entry: StateFlow<OvernightEntry?> = _entry

    private val opened = mutableSetOf<String?>()

    /** Checks on entry and again on each emission of [triggers]; ends when the caller's scope does. */
    suspend fun run(triggers: Flow<Unit>) {
        check()
        triggers.collect { check() }
    }

    suspend fun dismiss() {
        _entry.value?.let { seen(it.id) }
    }

    /** Called when the digest [id] is on screen, where null is the latest. */
    suspend fun opened(id: String?) {
        opened += id
        val shown = _entry.value ?: return
        if (id == null || id == shown.id) seen(shown.id)
    }

    private suspend fun seen(id: String) {
        _entry.value = null
        preferences.setDigestSeen(id)
    }

    private suspend fun check() {
        val digest =
            try {
                repository.latest()
            } catch (e: JmapException) {
                null
            } ?: return
        if (digest.items.isEmpty() || digest.id == preferences.settings.value.digestSeen) return
        if (null in opened || digest.id in opened) return seen(digest.id)
        _entry.value = digest.toEntry()
    }
}

private fun Digest.toEntry(): OvernightEntry {
    val (lists, people) = items.partition { it.isList }
    return OvernightEntry(id, items.size, people.map { it.displayName }.distinct(), lists.size)
}
