package com.verdenroz.corvid.core.model

import kotlinx.serialization.Serializable

/** What the compose screen opens for; a reply or forward names the message it answers by id. */
@Serializable
sealed interface ComposeIntent {
    @Serializable
    data object New : ComposeIntent

    /** A new message already addressed, with [subject] from a `mailto:` link when there is one. */
    @Serializable
    data class To(val address: String, val subject: String = "") : ComposeIntent

    @Serializable
    data class Reply(val emailId: String, val all: Boolean) : ComposeIntent

    @Serializable
    data class Forward(val emailId: String) : ComposeIntent

    /** A message saved on the server as a draft, named by its email id. */
    @Serializable
    data class Draft(val emailId: String) : ComposeIntent
}
