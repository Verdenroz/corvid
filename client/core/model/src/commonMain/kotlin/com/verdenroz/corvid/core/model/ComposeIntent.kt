package com.verdenroz.corvid.core.model

/** What the compose screen opens for; a reply or forward names the message it answers by id. */
sealed interface ComposeIntent {
    data object New : ComposeIntent

    /** A new message already addressed, with [subject] from a `mailto:` link when there is one. */
    data class To(val address: String, val subject: String = "") : ComposeIntent

    data class Reply(val emailId: String, val all: Boolean) : ComposeIntent

    data class Forward(val emailId: String) : ComposeIntent
}
