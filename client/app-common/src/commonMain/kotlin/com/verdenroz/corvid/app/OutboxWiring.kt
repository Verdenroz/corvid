package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.verdenroz.corvid.app.shell.OutboxBannerBar
import com.verdenroz.corvid.core.data.outbox.Contents
import com.verdenroz.corvid.core.data.outbox.OutboxAvailability
import com.verdenroz.corvid.core.data.outbox.OutboxEntry
import com.verdenroz.corvid.core.data.outbox.OutboxState
import com.verdenroz.corvid.feature.outbox.OutboxScreen
import com.verdenroz.corvid.feature.outbox.OutboxUnavailableScreen
import com.verdenroz.corvid.feature.outbox.OutboxViewModel
import org.koin.core.scope.Scope

/** [count] messages are not sent yet; [needsDecision] when any of them waits on the user rather than on the network. */
internal data class OutboxPending(
    val count: Int,
    val needsDecision: Boolean,
    val problem: OutboxAvailability? = null,
) {
    companion object {
        fun unavailable(problem: OutboxAvailability) = OutboxPending(0, needsDecision = false, problem = problem)

        fun of(
            entries: List<OutboxEntry>,
            quarantined: Int = 0,
        ): OutboxPending {
            val unsent = entries.filter { it.item.state !is OutboxState.Sent }
            val waitsOnUser = quarantined > 0 || unsent.any { it.item.state.waitsOnUser || it.contents !is Contents.Readable }
            return OutboxPending(unsent.size + quarantined, waitsOnUser)
        }
    }
}

private val OutboxState.waitsOnUser: Boolean
    get() = this == OutboxState.Unconfirmed || this is OutboxState.Rejected

@Composable
internal fun rememberOutboxPending(scope: Scope): OutboxPending {
    val availability by remember(scope) { scope.get<OutboxAccess>().availability }.collectAsState()
    val outbox =
        when (val current = availability) {
            null -> return OutboxPending(0, needsDecision = false)
            is OutboxAvailability.Available -> current.outbox
            else -> return OutboxPending.unavailable(current)
        }
    val entries by outbox.entries.collectAsState()
    val quarantined by outbox.quarantined.collectAsState()
    return remember(entries, quarantined) { OutboxPending.of(entries, quarantined.size) }
}

@Composable
internal fun OutboxPage(
    scope: Scope,
    navigator: Navigator,
    models: EntryModels,
    entryKey: String,
) {
    val availability by remember(scope) { scope.get<OutboxAccess>().availability }.collectAsState()
    when (val current = availability) {
        is OutboxAvailability.Available -> {
            val viewModel = models.of(entryKey, OutboxViewModel::class) { OutboxViewModel(current.outbox) }
            OutboxScreen(viewModel, onBack = navigator::back)
        }
        else -> OutboxUnavailableScreen(held = current == OutboxAvailability.HeldElsewhere, onBack = navigator::back)
    }
}

/** Dismissing hides the banner until the count or the need for a decision changes. */
@Composable
internal fun OutboxBanner(
    pending: OutboxPending,
    navigator: Navigator,
) {
    var dismissed by remember(pending) { mutableStateOf(false) }
    if ((pending.count == 0 && pending.problem == null) || dismissed) return
    OutboxBannerBar(
        pending,
        onOpen = { if (pending.problem == null && navigator.route != Route.Outbox) navigator.push(Route.Outbox) },
        onDismiss = { dismissed = true },
    )
}
