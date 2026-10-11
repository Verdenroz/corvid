package com.verdenroz.corvid.app

import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.new_mail_count
import com.verdenroz.corvid.appcommon.resources.new_mail_title
import com.verdenroz.corvid.core.data.InboxArrival
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.text.SystemTextResolver
import com.verdenroz.corvid.core.text.TextResolver
import com.verdenroz.corvid.core.text.uiPlural
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** Shows the one new-mail notification; each platform app provides one. It is given a count and never any mail. */
interface NewMailNotifier {
    fun show(
        title: String,
        text: String,
    )

    fun clear()
}

/**
 * Turns Inbox checks into one content-free notification with a running count. A message counts as new when it is
 * unread and arrived no earlier than the newest Inbox message the previous check saw, so mail moved back into the
 * Inbox, which keeps its old arrival time, never counts. The first check only sets that baseline.
 */
class NewMail(
    private val scan: suspend () -> List<InboxArrival>,
    private val notifier: NewMailNotifier,
    private val text: TextResolver = SystemTextResolver,
) {
    private val lock = Mutex()
    private var newest: Instant? = null
    private var known: Set<String> = emptySet()
    private var started = false
    private var pending = 0
    private var quiet = true

    /** [inboxInView] or ![enabled] clears the notification and suppresses the next ones until both change. */
    suspend fun attention(
        inboxInView: Boolean,
        enabled: Boolean,
    ) = lock.withLock {
        quiet = inboxInView || !enabled
        if (quiet) {
            pending = 0
            notifier.clear()
        }
    }

    suspend fun check() {
        val arrivals =
            try {
                scan()
            } catch (e: JmapException) {
                return
            }
        lock.withLock {
            val fresh = if (started) arrivals.filter { it.isNewSince(newest) } else emptyList()
            started = true
            remember(arrivals)
            if (fresh.isEmpty() || quiet) return
            pending += fresh.size
            notifier.show(text.resolve(uiText(Res.string.new_mail_title)), text.resolve(uiPlural(Res.plurals.new_mail_count, pending)))
        }
    }

    private fun InboxArrival.isNewSince(mark: Instant?): Boolean = !seen && id !in known && (mark == null || receivedAt >= mark)

    private fun remember(arrivals: List<InboxArrival>) {
        val latest = arrivals.maxOfOrNull { it.receivedAt } ?: return
        val mark = newest
        if (mark == null || latest > mark) newest = latest
        known = arrivals.mapTo(mutableSetOf()) { it.id }
    }
}
