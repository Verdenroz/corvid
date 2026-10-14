package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSender
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable

private const val SUGGESTIONS_PATH = "/corvid/suggestions"

@Serializable
private class WireSuggestions(val suggestions: List<WireSuggestion>)

@Serializable
private class WireSuggestion(
    val id: String,
    val sender: WireSender,
    val action: WireAction,
    val settled: Int,
    val hits: Int,
)

@Serializable
private class WireSender(val kind: String, val value: String)

@Serializable
private class WireAction(val kind: String, val mailboxId: String? = null)

/** The server's rule suggestions, behind `/corvid/suggestions` on the web tier. */
class SuggestionRepository(private val jmapClient: JmapClient) {
    /** Empty when the server lacks the route or has the feature off; a suggestion of a kind this app does not know is left out. */
    suspend fun list(): List<RuleSuggestion> {
        val body = jmapClient.webGetOrNull(SUGGESTIONS_PATH) ?: return emptyList()
        return body.decodeWeb(WireSuggestions.serializer(), "Suggestions") { wire ->
            wire.suggestions.mapNotNull { it.toSuggestion() }
        }
    }

    suspend fun accept(id: String) = post(id, "accept")

    suspend fun dismiss(id: String) = post(id, "dismiss")

    private suspend fun post(
        id: String,
        verb: String,
    ) {
        jmapClient.webSend(HttpMethod.Post, "$SUGGESTIONS_PATH/$id/$verb")
    }
}

private fun WireSuggestion.toSuggestion(): RuleSuggestion? {
    val senderKind = SuggestionSenderKind.entries.firstOrNull { it.wire == sender.kind } ?: return null
    val filing = action.toAction() ?: return null
    return RuleSuggestion(id, SuggestionSender(senderKind, sender.value), filing, settled, hits)
}

private fun WireAction.toAction(): SuggestionAction? =
    when (kind) {
        "archive" -> SuggestionAction.Archive
        "trash" -> SuggestionAction.Trash
        "folder" -> mailboxId?.let(SuggestionAction::Folder)
        else -> null
    }
