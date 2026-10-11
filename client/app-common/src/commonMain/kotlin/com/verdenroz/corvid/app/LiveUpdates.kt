package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.StateChange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.runningFold
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val BURST: Duration = 500.milliseconds
private const val HELD = 32

/** The pushes seen so far: how many, and the newest [HELD] of them. */
private class Seen(val count: Long, val recent: List<StateChange>)

/**
 * Runs [onChange] once per burst of server pushes that touch mail, until [changes] ends or the caller's scope does.
 * `echo` is true when every push in the burst announced an Email state that [isOwnEmailState] says this client's own
 * write produced. It is judged when the burst ends, since a push can arrive before the response to the write that
 * caused it. A terminal push failure ends it quietly: the auth latch already routes a 401 to sign-in.
 */
@OptIn(FlowPreview::class)
internal suspend fun followMailboxChanges(
    changes: Flow<StateChange>,
    burst: Duration = BURST,
    isOwnEmailState: (String) -> Boolean = { false },
    onChange: suspend (echo: Boolean) -> Unit,
) {
    var handled = 0L
    try {
        changes
            .filter { it.touches("Email", "Mailbox", "Thread") }
            .runningFold(Seen(0, emptyList())) { seen, change -> Seen(seen.count + 1, (seen.recent + change).takeLast(HELD)) }
            .drop(1)
            .debounce(burst)
            .collect { seen ->
                val fresh = seen.count - handled
                handled = seen.count
                val echo = fresh <= seen.recent.size && seen.recent.takeLast(fresh.toInt()).all { it.isEcho(isOwnEmailState) }
                onChange(echo)
            }
    } catch (e: JmapException) {
        return
    }
}

private fun StateChange.isEcho(isOwnEmailState: (String) -> Boolean): Boolean =
    changed.values.all { states -> states["Email"]?.let(isOwnEmailState) == true }
