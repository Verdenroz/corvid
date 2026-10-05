package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.stamp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.SenderKind
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.core.ui.provenance.SenderAvatar
import com.verdenroz.corvid.core.ui.provenance.sealDescription
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_back
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_forward
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_gone
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_reply
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_reply_all
import com.verdenroz.corvid.feature.mailbox.resources.reader_cc
import com.verdenroz.corvid.feature.mailbox.resources.reader_to
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
fun ReaderScreen(
    repository: MailboxRepository,
    emailId: String,
    onBack: () -> Unit,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val viewModel = remember(repository, emailId) { ReaderViewModel(repository, emailId) }
    val state by viewModel.state.collectAsState()

    val wide = LocalWideLayout.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ActionBar(wide, onBack)
            when (val current = state) {
                is ReaderUiState.Loading -> Unit
                is ReaderUiState.Failed -> QuietPane(current.message.asString())
                is ReaderUiState.Gone -> QuietPane(stringResource(Res.string.mailbox_gone))
                is ReaderUiState.Loaded ->
                    Reader(
                        current.email,
                        repository,
                        wide,
                        onCompose,
                        onOpenSender,
                    ) { guess -> TeachControls(guess, current.answering, current.answerError, viewModel::answer) }
            }
        }
    }
}

@Composable
private fun ActionBar(
    wide: Boolean,
    onBack: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(if (wide) 48.dp else 52.dp).padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(CorvidIcons.Back, contentDescription = stringResource(Res.string.mailbox_back), tint = CorvidTheme.colors.inkMuted)
            }
        }
        if (wide) HorizontalDivider(color = CorvidTheme.colors.line)
    }
}

@Composable
private fun Reader(
    email: EmailDetail,
    repository: MailboxRepository,
    wide: Boolean,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
    teach: @Composable (Label.Guess) -> Unit,
) {
    val colors = CorvidTheme.colors
    val padding =
        if (wide) {
            Modifier.padding(
                start = 32.dp,
                end = 32.dp,
                top = 22.dp,
                bottom = 36.dp,
            )
        } else {
            Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp)
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(padding.widthIn(max = 860.dp)) {
            Text(
                email.subject.ifEmpty { stringResource(Res.string.mailbox_no_subject) },
                style = if (wide) CorvidTheme.type.headline else CorvidTheme.type.title,
                color = if (email.provenance.suspect) colors.danger else colors.ink,
            )
            Spacer(Modifier.height(16.dp))
            SenderLine(email, onOpenSender)
            Spacer(Modifier.height(16.dp))
            Custody(email, wide, teach)
            MessageBody(email, repository, Modifier.padding(top = 18.dp).widthIn(max = 640.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                PillButton(
                    stringResource(Res.string.mailbox_reply),
                    CorvidIcons.Reply,
                    { onCompose(ComposeIntent.Reply(email.id, all = false)) },
                )
                PillButton(
                    stringResource(Res.string.mailbox_reply_all),
                    CorvidIcons.ReplyAll,
                    { onCompose(ComposeIntent.Reply(email.id, all = true)) },
                )
                PillButton(stringResource(Res.string.mailbox_forward), CorvidIcons.Forward, { onCompose(ComposeIntent.Forward(email.id)) })
            }
        }
    }
}

@Composable
private fun SenderLine(
    email: EmailDetail,
    onOpenSender: (String) -> Unit,
) {
    val open = email.from?.email?.let { { onOpenSender(it) } }
    val colors = CorvidTheme.colors
    val seal = email.provenance.seal
    val zone = remember { TimeZone.currentSystemDefault() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SenderAvatar(email.from.displayName(), email.provenance.sender is SenderKind.Source, seal, onClick = open)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    email.from.displayName(),
                    modifier = if (open == null) Modifier else Modifier.clickable(onClick = open),
                    style = CorvidTheme.type.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (seal) {
                    is Seal.Signed -> Icon(CorvidIcons.Seal, sealDescription(seal), tint = colors.accent, modifier = Modifier.size(15.dp))
                    is Seal.Failed -> Icon(CorvidIcons.Alert, sealDescription(seal), tint = colors.danger, modifier = Modifier.size(15.dp))
                    is Seal.Unsigned -> Unit
                }
                email.from?.email?.takeIf { email.from?.name != null || email.provenance.suspect }?.let {
                    Text(
                        senderAddress(it, email.provenance.suspect).asString(),
                        style = CorvidTheme.type.meta,
                        color = if (email.provenance.suspect) colors.danger else colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RecipientLine(Res.string.reader_to, email.to)
            RecipientLine(Res.string.reader_cc, email.cc)
        }
        Text(stamp(email.receivedAt, zone, Clock.System.now()).asString(), style = CorvidTheme.type.meta, color = colors.inkMuted)
    }
}

@Composable
private fun RecipientLine(
    line: StringResource,
    recipients: List<EmailAddress>,
) {
    if (recipients.isEmpty()) return
    Text(
        stringResource(line, recipients.map { it.displayName() }.joinToString()),
        style = CorvidTheme.type.meta,
        color = CorvidTheme.colors.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
