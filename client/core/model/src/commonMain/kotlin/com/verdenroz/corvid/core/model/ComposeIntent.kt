package com.verdenroz.corvid.core.model

/** What the compose screen opens for; a reply or forward names the message it answers by id. */
sealed interface ComposeIntent {
    data object New : ComposeIntent

    data class Reply(val emailId: String, val all: Boolean) : ComposeIntent

    data class Forward(val emailId: String) : ComposeIntent
}
