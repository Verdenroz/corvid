package com.verdenroz.corvid.app

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.verdenroz.corvid.core.model.ComposeIntent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A screen on the unlocked back stack; the last entry is the one showing. */
@Serializable
sealed interface Route {
    @Serializable
    data class Home(val tab: HomeTab) : Route

    @Serializable
    data class Reader(val emailId: String) : Route

    @Serializable
    data class Compose(val intent: ComposeIntent) : Route

    @Serializable
    data object Filters : Route

    /** Messages matching [query]; a blank query lists nothing. */
    @Serializable
    data class Search(val query: String) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Notices : Route

    /** The card for one sender; a side sheet when wide, a page when narrow. */
    @Serializable
    data class Sender(val address: String) : Route
}

/** Saves only ids, so a restored stack refetches every message rather than trusting a stale copy. */
val RouteStackSaver: Saver<SnapshotStateList<Route>, List<String>> =
    Saver(save = { stack -> stack.map(Route::encode) }, restore = { saved -> saved.map(::decodeRoute).toMutableStateList() })

internal fun Route.encode(): String = Json.encodeToString(Route.serializer(), this)

internal fun decodeRoute(saved: String): Route = Json.decodeFromString(Route.serializer(), saved)
