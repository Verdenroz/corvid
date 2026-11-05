package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.verdenroz.corvid.app.shell.OutboxBannerBar
import com.verdenroz.corvid.core.data.outbox.Contents
import com.verdenroz.corvid.core.data.outbox.Outbox
import com.verdenroz.corvid.core.data.outbox.OutboxEntry
import com.verdenroz.corvid.core.data.outbox.OutboxState
import org.koin.core.scope.Scope

/** [count] messages are not sent yet; [needsDecision] when any of them waits on the user rather than on the network. */
internal data class OutboxPending(
    val count: Int,
    val needsDecision: Boolean,
) {
    companion object {
        fun of(entries: List<OutboxEntry>): OutboxPending {
            val unsent = entries.filter { it.item.state !is OutboxState.Sent }
            val waitsOnUser = unsent.any { it.item.state.waitsOnUser || it.contents !is Contents.Readable }
            return OutboxPending(unsent.size, waitsOnUser)
        }
    }
}

private val OutboxState.waitsOnUser: Boolean
    get() = this == OutboxState.Unconfirmed || this is OutboxState.Rejected

@Composable
internal fun rememberOutboxPending(scope: Scope): OutboxPending {
    val outbox = remember(scope) { scope.get<Outbox>() }
    val entries by outbox.entries.collectAsState()
    return remember(entries) { OutboxPending.of(entries) }
}

/** Dismissing hides the banner until the count or the need for a decision changes. */
@Composable
internal fun OutboxBanner(
    pending: OutboxPending,
    navigator: Navigator,
) {
    var dismissed by remember(pending) { mutableStateOf(false) }
    if (pending.count == 0 || dismissed) return
    OutboxBannerBar(
        pending,
        onOpen = { if (navigator.route != Route.Outbox) navigator.push(Route.Outbox) },
        onDismiss = { dismissed = true },
    )
}
