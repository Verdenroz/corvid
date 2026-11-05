package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.prefs.DevicePreferences
import org.koin.core.scope.Scope

/** The device key of this unlock, held by reference so the outbox sees it zeroed when the key is dropped. */
class UnlockedDevice(val key: ByteArray, val domain: String)

fun interface OutboxUnlock {
    suspend fun open(
        scope: Scope,
        deviceKey: ByteArray,
        domain: String,
    )
}

/** The outbox of this unlock, or null while another process holds its directory. */
class OutboxAccess(val outbox: Outbox?)

/** Stores the seal key before anything can be queued, then sends what an earlier unlock left behind. */
internal suspend fun openOutbox(
    preferences: DevicePreferences,
    scope: Scope,
    deviceKey: ByteArray,
    domain: String,
) {
    scope.declare(UnlockedDevice(deviceKey, domain))
    val outbox = scope.get<OutboxAccess>().outbox ?: return
    if (outbox.shouldStoreKey(preferences.outboxKey())) preferences.setOutboxKey(OutboxSealer.keyFor(deviceKey).encoded())
    outbox.drainSoon()
}
