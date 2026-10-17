package com.verdenroz.corvid.core.ui.suggestion

import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.SuggestionAction
import com.verdenroz.corvid.core.model.folderPath
import com.verdenroz.corvid.core.text.UiText

/** The folder a suggestion files into, as the user sees its path; null for archive and trash. */
fun SuggestionAction.folderText(folders: List<MailboxRef>): UiText? =
    (this as? SuggestionAction.Folder)?.let { UiText.FromServer(folders.folderPath(it.mailboxId)) }
