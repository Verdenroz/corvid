package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxStore
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

/** Stores the seal key before anything can be queued, then sends what an earlier unlock left behind. */
internal suspend fun openOutbox(
    preferences: DevicePreferences,
    scope: Scope,
    deviceKey: ByteArray,
    domain: String,
) {
    scope.declare(UnlockedDevice(deviceKey, domain))
    if (OutboxSealer.shouldStoreKey(deviceKey, preferences.outboxKey(), scope.get<OutboxStore>())) {
        preferences.setOutboxKey(OutboxSealer.keyFor(deviceKey).encoded())
    }
    scope.get<Outbox>().drainSoon()
}
