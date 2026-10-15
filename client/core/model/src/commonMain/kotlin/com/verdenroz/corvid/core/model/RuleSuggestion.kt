package com.verdenroz.corvid.core.model

/** [wire] is the spelling the server writes. */
enum class SuggestionSenderKind(val wire: String) {
    List("list"),
    From("from"),
}

data class SuggestionSender(val kind: SuggestionSenderKind, val value: String)

sealed interface SuggestionAction {
    data object Archive : SuggestionAction

    data object Trash : SuggestionAction

    data class Folder(val mailboxId: String) : SuggestionAction
}

/**
 * A rule the server mined from what the user leaves unread: [hits] of the [settled] messages from [sender] were handled this way.
 * [history] is one flag per recent message, oldest first, true where it matched; [spanDays] is the time it covers.
 */
data class RuleSuggestion(
    val id: String,
    val sender: SuggestionSender,
    val action: SuggestionAction,
    val settled: Int,
    val hits: Int,
    val history: List<Boolean> = emptyList(),
    val spanDays: Int = 0,
)
