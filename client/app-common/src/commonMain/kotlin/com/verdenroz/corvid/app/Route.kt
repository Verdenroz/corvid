package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.model.ComposeIntent

/** A screen on the unlocked back stack; the last entry is the one showing. */
sealed interface Route {
    data object Inbox : Route

    data class Reader(val emailId: String) : Route

    data class Compose(val intent: ComposeIntent) : Route

    data object Filters : Route
}
