package com.verdenroz.corvid.core.model

/** What the compose screen opens for; a reply or forward carries the message it answers. */
sealed interface ComposeIntent {
    data object New : ComposeIntent

    data class Reply(val original: EmailDetail, val all: Boolean) : ComposeIntent

    data class Forward(val original: EmailDetail) : ComposeIntent
}
