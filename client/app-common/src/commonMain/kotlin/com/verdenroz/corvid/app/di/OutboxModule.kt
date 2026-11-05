package com.verdenroz.corvid.app.di

import com.verdenroz.corvid.app.OfflineOutbox
import com.verdenroz.corvid.app.OutboxAccess
import com.verdenroz.corvid.app.OutboxUnlock
import com.verdenroz.corvid.app.UnlockedDevice
import com.verdenroz.corvid.app.openOutbox
import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import com.verdenroz.corvid.core.data.outbox.OutboxQueue
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.data.outbox.OutboxSender
import com.verdenroz.corvid.core.data.outbox.OutboxStore
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.OutboxDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import org.koin.dsl.onClose

val outboxModule =
    module {
        single { OfflineOutbox(get(), get()) }
        factory { OutboxUnlock { scope, key, domain -> openOutbox(get<DevicePreferences>(), scope, key, domain) } }
        scope<UnlockedScopeMarker> {
            scoped {
                val device = get<UnlockedDevice>()
                val directory = get<OutboxDirectory>().path
                val client = get<JmapClient>()
                val compose = get<ComposeRepository>()
                OutboxAccess(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                    val store = OutboxStore.open(directory)
                    if (store == null) {
                        OutboxAvailability.HeldElsewhere
                    } else {
                        val queue = OutboxQueue(store, OutboxSealer.keyFor(device.key), device.domain)
                        val sender = OutboxSender(store, client, compose, device.key)
                        OutboxAvailability.Available(
                            Outbox(store, queue, sender, device.key, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                        )
                    }
                }
            }.onClose { it?.close() }
        }
    }
