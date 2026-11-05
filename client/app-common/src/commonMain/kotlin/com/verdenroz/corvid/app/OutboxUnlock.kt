package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.prefs.DevicePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope
import java.io.IOException

/** The device key of this unlock, held by reference so the outbox sees it zeroed when the key is dropped. */
class UnlockedDevice(val key: ByteArray, val domain: String)

fun interface OutboxUnlock {
    suspend fun open(
        scope: Scope,
        deviceKey: ByteArray,
        domain: String,
    )
}

/** Opens the outbox on [scope], which is an IO scope, so the directory and its lock are never touched by the caller. */
class OutboxAccess(
    private val scope: CoroutineScope,
    open: () -> OutboxAvailability,
) : AutoCloseable {
    private val _availability = MutableStateFlow<OutboxAvailability?>(null)

    /** Null until the first attempt to open has finished. */
    val availability: StateFlow<OutboxAvailability?> = _availability

    init {
        scope.launch {
            val opened =
                try {
                    open()
                } catch (_: IOException) {
                    OutboxAvailability.Failed
                }
            if (isActive) _availability.value = opened else (opened as? OutboxAvailability.Available)?.outbox?.close()
        }
    }

    suspend fun await(): OutboxAvailability = availability.filterNotNull().first()

    /** Gives up the outbox after its setup went wrong, so the rest of the app sees it as unavailable. */
    fun fail() {
        (_availability.value as? OutboxAvailability.Available)?.outbox?.close()
        _availability.value = OutboxAvailability.Failed
    }

    override fun close() {
        scope.cancel()
        (_availability.value as? OutboxAvailability.Available)?.outbox?.close()
    }
}

/** Stores the seal key before anything can be queued, then sends what an earlier unlock left behind. */
internal suspend fun openOutbox(
    preferences: DevicePreferences,
    scope: Scope,
    deviceKey: ByteArray,
    domain: String,
) {
    scope.declare(UnlockedDevice(deviceKey, domain))
    val access = scope.get<OutboxAccess>()
    val outbox = (access.await() as? OutboxAvailability.Available)?.outbox ?: return
    try {
        if (outbox.shouldStoreKey(preferences.outboxKey())) preferences.setOutboxKey(OutboxSealer.keyFor(deviceKey).encoded())
    } catch (_: IOException) {
        return access.fail()
    }
    outbox.drainSoon()
}
