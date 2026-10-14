package com.verdenroz.corvid.core.model

enum class SuggestionSenderKind { List, From }

enum class SuggestionActionKind { Archive, Trash, Folder }

data class SuggestionSender(val kind: SuggestionSenderKind, val value: String)

/** [mailboxId] names the folder when [kind] is [SuggestionActionKind.Folder]. */
data class SuggestionAction(val kind: SuggestionActionKind, val mailboxId: String?)

/** A rule the server mined from what the user leaves unread: [hits] of the [settled] messages from [sender] were handled this way. */
data class RuleSuggestion(
    val id: String,
    val sender: SuggestionSender,
    val action: SuggestionAction,
    val settled: Int,
    val hits: Int,
)
