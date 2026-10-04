package com.verdenroz.corvid.app

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.verdenroz.corvid.core.model.ComposeIntent

/** A screen on the unlocked back stack; the last entry is the one showing. */
sealed interface Route {
    data object Inbox : Route

    data class Reader(val emailId: String) : Route

    data class Compose(val intent: ComposeIntent) : Route

    data object Filters : Route
}

/** Saves only ids, so a restored stack refetches every message rather than trusting a stale copy. */
val RouteStackSaver: Saver<SnapshotStateList<Route>, List<String>> =
    Saver(save = { stack -> stack.map(Route::encode) }, restore = { saved -> saved.map(::decodeRoute).toMutableStateList() })

internal fun Route.encode(): String =
    when (this) {
        is Route.Inbox -> "inbox"
        is Route.Filters -> "filters"
        is Route.Reader -> "reader:$emailId"
        is Route.Compose ->
            when (intent) {
                is ComposeIntent.New -> "compose"
                is ComposeIntent.Reply -> "${if (intent.all) "replyall" else "reply"}:${intent.emailId}"
                is ComposeIntent.Forward -> "forward:${intent.emailId}"
            }
    }

internal fun decodeRoute(saved: String): Route {
    val kind = saved.substringBefore(':')
    val id = saved.substringAfter(':')
    return when (kind) {
        "inbox" -> Route.Inbox
        "filters" -> Route.Filters
        "reader" -> Route.Reader(id)
        "compose" -> Route.Compose(ComposeIntent.New)
        "reply" -> Route.Compose(ComposeIntent.Reply(id, all = false))
        "replyall" -> Route.Compose(ComposeIntent.Reply(id, all = true))
        "forward" -> Route.Compose(ComposeIntent.Forward(id))
        else -> error("unknown saved route $saved")
    }
}
