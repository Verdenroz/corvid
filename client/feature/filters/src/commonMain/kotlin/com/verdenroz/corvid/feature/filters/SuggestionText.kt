package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.suggestion.folderText
import com.verdenroz.corvid.core.ui.suggestion.spanClause
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.suggestion_act_archive
import com.verdenroz.corvid.feature.filters.resources.suggestion_act_folder
import com.verdenroz.corvid.feature.filters.resources.suggestion_act_trash
import com.verdenroz.corvid.feature.filters.resources.suggestion_caption
import com.verdenroz.corvid.feature.filters.resources.suggestion_caption_archived
import com.verdenroz.corvid.feature.filters.resources.suggestion_caption_filed
import com.verdenroz.corvid.feature.filters.resources.suggestion_caption_trashed
import com.verdenroz.corvid.feature.filters.resources.suggestion_cond_from
import com.verdenroz.corvid.feature.filters.resources.suggestion_cond_list

/** The two halves of "When [condition], [action]" that the card sets in bold. */
internal data class RuleSentence(val condition: UiText, val action: UiText)

internal fun RuleSuggestion.sentence(folders: List<MailboxRef>): RuleSentence {
    val value = UiText.FromServer(sender.value)
    val field = if (sender.kind == SuggestionSenderKind.List) Res.string.suggestion_cond_list else Res.string.suggestion_cond_from
    val condition = uiText(field, value)
    val filing =
        action.folderText(folders)?.let { uiText(Res.string.suggestion_act_folder, it) }
            ?: uiText(if (action == SuggestionAction.Archive) Res.string.suggestion_act_archive else Res.string.suggestion_act_trash)
    return RuleSentence(condition, filing)
}

/** How often the messages were handled this way, and over how long when the server said. */
internal fun RuleSuggestion.caption(folders: List<MailboxRef>): UiText {
    val resource =
        when (action) {
            SuggestionAction.Archive -> Res.plurals.suggestion_caption_archived
            SuggestionAction.Trash -> Res.plurals.suggestion_caption_trashed
            is SuggestionAction.Folder -> Res.plurals.suggestion_caption_filed
        }
    val folder = action.folderText(folders)
    val counted = UiText.Plural(resource, hits, listOfNotNull(hits, settled, folder))
    return spanClause(spanDays)?.let { uiText(Res.string.suggestion_caption, counted, it) } ?: counted
}
