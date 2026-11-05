package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.verdenroz.corvid.core.data.outbox.OutboxWriter
import com.verdenroz.corvid.core.data.outbox.SealKey
import com.verdenroz.corvid.core.data.outbox.WriteOutcome
import com.verdenroz.corvid.core.data.outbox.waitingInOutbox
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.OutboxDirectory
import com.verdenroz.corvid.feature.compose.OfflineComposeScreen
import com.verdenroz.corvid.feature.compose.OfflineComposeState

/** Queues and counts mail while the device key is not held, from the stored public key and the outbox directory alone. */
class OfflineOutbox(
    private val preferences: DevicePreferences,
    private val directory: OutboxDirectory,
) {
    suspend fun write(
        draft: Draft,
        domain: String,
    ): WriteOutcome {
        val key = SealKey.decode(checkNotNull(preferences.outboxKey()) { "no seal key is stored" })
        return OutboxWriter(directory.path, key, domain).write(draft)
    }

    suspend fun waiting(): Int? = waitingInOutbox(directory.path)
}

/**
 * Shows [pane] with the waiting count and, when a seal key is stored, a way to write a message; while the user is
 * writing, shows the writer in its place. What was typed is dropped once the gate leaves the locked and unreachable states.
 */
@Composable
internal fun OfflineGate(
    state: AppLockState,
    server: String?,
    canWrite: Boolean,
    outbox: OfflineOutbox,
    onWriting: (Boolean) -> Unit,
    pane: @Composable (waiting: Int?, onWrite: (() -> Unit)?) -> Unit,
) {
    val form = remember { OfflineComposeState() }
    var writing by remember { mutableStateOf(false) }
    var written by remember { mutableIntStateOf(0) }
    val gated = state is AppLockState.Locked || state is AppLockState.Unreachable
    val ended = state is AppLockState.Unlocked || state is AppLockState.SignedOut || state is AppLockState.Revoked
    LaunchedEffect(ended) {
        if (ended) {
            writing = false
            form.clear()
        }
    }
    LaunchedEffect(writing) { onWriting(writing) }
    val waiting by produceState<Int?>(null, gated, written) { value = if (gated) outbox.waiting() else null }
    if (writing && gated && server != null) {
        OfflineComposeScreen(
            form,
            onSend = { outbox.write(it, server) },
            onClose = {
                writing = false
                written++
            },
        )
    } else {
        pane(waiting, if (canWrite && server != null) ({ writing = true }) else null)
    }
}
