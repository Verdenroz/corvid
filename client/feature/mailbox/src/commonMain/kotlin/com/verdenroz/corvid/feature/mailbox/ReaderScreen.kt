package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_back
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_gone
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import org.jetbrains.compose.resources.stringResource

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
                is ReaderUiState.Loaded -> ThreadColumn(current, repository, wide, viewModel::toggle, viewModel::answer, onCompose, onOpenSender)
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
private fun ThreadColumn(
    state: ReaderUiState.Loaded,
    repository: MailboxRepository,
    wide: Boolean,
    onToggle: (String) -> Unit,
    onAnswer: (String, Correction) -> Unit,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val gutter = if (wide) 32.dp else 18.dp
    val threaded = state.messages.size > 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            state.newest.subject.ifEmpty { stringResource(Res.string.mailbox_no_subject) },
            style = if (wide) CorvidTheme.type.headline else CorvidTheme.type.title,
            color = CorvidTheme.colors.ink,
            modifier =
                Modifier.padding(
                    start = gutter,
                    end = gutter,
                    top = if (wide) 22.dp else 6.dp,
                    bottom = 16.dp,
                ).widthIn(max = 860.dp),
        )
        state.messages.forEach { email ->
            ThreadMessage(
                email = email,
                repository = repository,
                expanded = email.id in state.expanded,
                newest = email.id == state.newest.id,
                threaded = threaded,
                wide = wide,
                teach = state.teach[email.id] ?: TeachState(),
                onToggle = { onToggle(email.id) },
                onAnswer = { onAnswer(email.id, it) },
                onCompose = onCompose,
                onOpenSender = onOpenSender,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}
