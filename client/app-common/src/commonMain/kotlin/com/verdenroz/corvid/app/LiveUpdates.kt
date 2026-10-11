package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.StateChange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val BURST: Duration = 500.milliseconds

/**
 * Runs [onChange] once per burst of server pushes that touch mail, until [changes] ends or the caller's scope does.
 * A terminal push failure ends it quietly: the auth latch already routes a 401 to sign-in.
 */
@OptIn(FlowPreview::class)
internal suspend fun followMailboxChanges(
    changes: Flow<StateChange>,
    burst: Duration = BURST,
    onChange: suspend () -> Unit,
) {
    try {
        changes.filter { it.touches("Email", "Mailbox", "Thread") }.debounce(burst).collect { onChange() }
    } catch (e: JmapException) {
        return
    }
}
