package com.verdenroz.corvid.feature.filters

import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.model.SuggestionActionKind
import com.verdenroz.corvid.core.model.SuggestionSenderKind
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.suggestion_archived
import com.verdenroz.corvid.feature.filters.resources.suggestion_filed
import com.verdenroz.corvid.feature.filters.resources.suggestion_summary
import com.verdenroz.corvid.feature.filters.resources.suggestion_trashed
import com.verdenroz.corvid.feature.filters.resources.suggestion_who_list

internal fun RuleSuggestion.summary(folders: List<MailboxRef>): UiText? {
    val who = UiText.FromServer(sender.value)
    val from = if (sender.kind == SuggestionSenderKind.List) uiText(Res.string.suggestion_who_list, who) else who
    val did =
        when (action.kind) {
            SuggestionActionKind.Archive -> uiText(Res.string.suggestion_archived)
            SuggestionActionKind.Trash -> uiText(Res.string.suggestion_trashed)
            SuggestionActionKind.Folder -> {
                val folder = folders.firstOrNull { it.id == action.mailboxId } ?: return null
                uiText(Res.string.suggestion_filed, UiText.FromServer(folder.displayPath()))
            }
        }
    return uiText(Res.string.suggestion_summary, hits, settled, from, did)
}
