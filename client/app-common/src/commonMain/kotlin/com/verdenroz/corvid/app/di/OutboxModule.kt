package com.verdenroz.corvid.app.di

import com.verdenroz.corvid.app.OutboxAccess
import com.verdenroz.corvid.app.OutboxUnlock
import com.verdenroz.corvid.app.UnlockedDevice
import com.verdenroz.corvid.app.openOutbox
import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.OutboxDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import org.koin.dsl.onClose

val outboxModule =
    module {
        factory { OutboxUnlock { scope, key, domain -> openOutbox(get<DevicePreferences>(), scope, key, domain) } }
        scope<UnlockedScopeMarker> {
            scoped {
                val device = get<UnlockedDevice>()
                val outbox =
                    OutboxStore.open(get<OutboxDirectory>().path)?.let { store ->
                        val queue = OutboxQueue(store, OutboxSealer.keyFor(device.key), device.domain)
                        val sender = OutboxSender(store, get(), get(), device.key)
                        Outbox(store, queue, sender, device.key, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                    }
                OutboxAccess(outbox)
            }.onClose { it?.outbox?.close() }
        }
    }
