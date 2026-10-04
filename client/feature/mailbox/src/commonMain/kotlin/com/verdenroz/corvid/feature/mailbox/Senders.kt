package com.verdenroz.corvid.feature.mailbox

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.model.SourceKind
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_sender
import com.verdenroz.corvid.feature.mailbox.resources.source_automated
import com.verdenroz.corvid.feature.mailbox.resources.source_mailing_list
import com.verdenroz.corvid.feature.mailbox.resources.source_newsletter
import com.verdenroz.corvid.feature.mailbox.resources.source_own_server
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EmailAddress?.displayName(): String =
    this?.name?.takeIf {
        it.isNotBlank()
    } ?: this?.email ?: stringResource(Res.string.mailbox_no_sender)

@Composable
internal fun SenderKind.sourceLabel(): String? =
    (this as? SenderKind.Source)?.let {
        stringResource(
            when (it.kind) {
                SourceKind.OwnServer -> Res.string.source_own_server
                SourceKind.MailingList -> Res.string.source_mailing_list
                SourceKind.Newsletter -> Res.string.source_newsletter
                SourceKind.Automated -> Res.string.source_automated
            },
        )
    }
