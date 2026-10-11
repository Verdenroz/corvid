package com.verdenroz.corvid.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.fileSize
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.AttachmentTile
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.GuardBack
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_attach
import com.verdenroz.corvid.feature.compose.resources.compose_bcc
import com.verdenroz.corvid.feature.compose.resources.compose_cc
import com.verdenroz.corvid.feature.compose.resources.compose_close
import com.verdenroz.corvid.feature.compose.resources.compose_discard_draft_question
import com.verdenroz.corvid.feature.compose.resources.compose_discard_question
import com.verdenroz.corvid.feature.compose.resources.compose_message
import com.verdenroz.corvid.feature.compose.resources.compose_not_saved
import com.verdenroz.corvid.feature.compose.resources.compose_retry_save
import com.verdenroz.corvid.feature.compose.resources.compose_send
import com.verdenroz.corvid.feature.compose.resources.compose_subject
import com.verdenroz.corvid.feature.compose.resources.compose_title_draft
import com.verdenroz.corvid.feature.compose.resources.compose_title_forward
import com.verdenroz.corvid.feature.compose.resources.compose_title_new
import com.verdenroz.corvid.feature.compose.resources.compose_title_reply
import com.verdenroz.corvid.feature.compose.resources.compose_to
import com.verdenroz.corvid.feature.compose.resources.compose_uploading
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** [model] gives the back-stack entry's view model, made on first call from the form saved before the screen was recreated. */
@Composable
fun ComposeScreen(
    files: AttachmentFiles,
    intent: ComposeIntent,
    onClose: () -> Unit,
    model: (restore: ComposeRestore?) -> ComposeViewModel,
) {
    var restore by rememberSaveable(stateSaver = ComposeRestoreSaver) { mutableStateOf<ComposeRestore?>(null) }
    val viewModel = model(restore)
    DisposableEffect(viewModel) { onDispose(viewModel::release) }
    val state by viewModel.state.collectAsState()
    (state as? ComposeUiState.Editing)?.let { editing ->
        val current = ComposeRestore(editing.form, editing.draftId)
        if (current != restore) SideEffect { restore = current }
    }
    val close = { if (viewModel.requestClose()) onClose() }
    // Escape arrives here as back too, so an open suggestion list closes before the screen does.
    GuardBack { viewModel.dismissSuggestions() || !viewModel.requestClose() }
    val recipients =
        remember(viewModel) {
            RecipientActions(
                viewModel::editRecipients,
                viewModel::moveHighlight,
                viewModel::acceptSuggestion,
                viewModel::dismissSuggestions,
            )
        }
    val scope = rememberCoroutineScope()
    val attach = { scope.launch { viewModel.attach(files.pick()) } }

    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        val editing = state as? ComposeUiState.Editing
        Bar(
            intent,
            sending = editing?.let { it.sending || it.uploading.isNotEmpty() } ?: true,
            onClose = close,
            onAttach = if (editing == null) null else ({ attach() }),
            onSend = viewModel::send,
        )
        when (val current = state) {
            is ComposeUiState.Loading -> Unit
            is ComposeUiState.Sent -> LaunchedEffect(Unit) { onClose() }
            is ComposeUiState.Failed -> QuietPane(current.message.asString())
            is ComposeUiState.Editing -> {
                if (current.confirmingDiscard) {
                    DiscardQuestion(
                        stringResource(
                            if (current.draftId == null) Res.string.compose_discard_question else Res.string.compose_discard_draft_question,
                        ),
                        onConfirm = {
                            viewModel.discard()
                            onClose()
                        },
                        onCancel = viewModel::keepEditing,
                    )
                }
                Fields(current, recipients, onEdit = viewModel::edit, onRetrySave = viewModel::retrySave, onDetach = viewModel::detach)
            }
        }
    }
}

@Composable
private fun Bar(
    intent: ComposeIntent,
    sending: Boolean,
    onClose: () -> Unit,
    onAttach: (() -> Unit)?,
    onSend: () -> Unit,
) {
    val title =
        when (intent) {
            is ComposeIntent.Reply -> Res.string.compose_title_reply
            is ComposeIntent.Forward -> Res.string.compose_title_forward
            is ComposeIntent.Draft -> Res.string.compose_title_draft
            else -> Res.string.compose_title_new
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        IconButton(
            onClick = onClose,
        ) { Icon(CorvidIcons.Close, stringResource(Res.string.compose_close), tint = CorvidTheme.colors.inkMuted) }
        Text(stringResource(title), style = CorvidTheme.type.action, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        onAttach?.let {
            IconButton(onClick = it, modifier = Modifier.padding(end = 4.dp)) {
                Icon(CorvidIcons.Paperclip, stringResource(Res.string.compose_attach), tint = CorvidTheme.colors.inkMuted)
            }
        }
        PrimaryButton(stringResource(Res.string.compose_send), onClick = onSend, icon = CorvidIcons.Send, enabled = !sending)
    }
    HorizontalDivider(color = CorvidTheme.colors.line)
}

@Composable
private fun Fields(
    state: ComposeUiState.Editing,
    recipients: RecipientActions,
    onEdit: (ComposeForm.() -> ComposeForm) -> Unit,
    onRetrySave: () -> Unit,
    onDetach: (Attachment) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.saveFailed) NotSaved(onRetrySave)
        state.error?.let {
            Text(
                it.asString(),
                style = CorvidTheme.type.meta,
                color = CorvidTheme.colors.danger,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        var copies by rememberSaveable { mutableStateOf(state.form.cc.isNotEmpty() || state.form.bcc.isNotEmpty()) }
        RecipientInput(
            stringResource(Res.string.compose_to),
            RecipientField.To,
            state.form.to,
            state.suggestions,
            recipients,
            onShowCopies = if (copies) null else ({ copies = true }),
        )
        if (copies) {
            RecipientInput(stringResource(Res.string.compose_cc), RecipientField.Cc, state.form.cc, state.suggestions, recipients)
            RecipientInput(stringResource(Res.string.compose_bcc), RecipientField.Bcc, state.form.bcc, state.suggestions, recipients)
        }
        Field(stringResource(Res.string.compose_subject), state.form.subject) { onEdit { copy(subject = it) } }
        Attachments(state, onDetach)
        Body(state.form.body) { onEdit { copy(body = it) } }
    }
}

@Composable
private fun Attachments(
    state: ComposeUiState.Editing,
    onDetach: (Attachment) -> Unit,
) {
    val colors = CorvidTheme.colors
    if (state.form.attachments.isEmpty() && state.uploading.isEmpty() && state.attachError == null) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.attachError?.let { Text(it.asString(), style = CorvidTheme.type.meta, color = colors.danger) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.form.attachments.forEach { file ->
                AttachmentTile(file.name, file.type, fileSize(file.size).asString(), onRemove = { onDetach(file) })
            }
            state.uploading.forEach { name ->
                AttachmentTile(name, "", stringResource(Res.string.compose_uploading), detailColor = colors.inkFaint)
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.line)
}

@Composable
private fun NotSaved(onRetry: () -> Unit) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(stringResource(Res.string.compose_not_saved), style = CorvidTheme.type.meta, color = colors.inkMuted)
        Text(
            stringResource(Res.string.compose_retry_save),
            style = CorvidTheme.type.metaStrong,
            color = colors.accent,
            modifier = Modifier.clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    val colors = CorvidTheme.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) {
            Text(label, style = CorvidTheme.type.name, color = colors.inkMuted, modifier = Modifier.width(64.dp))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = CorvidTheme.type.name.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun Body(
    value: String,
    onChange: (String) -> Unit,
) {
    val colors = CorvidTheme.colors
    val placeholder = stringResource(Res.string.compose_message)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = CorvidTheme.type.body.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(16.dp),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = CorvidTheme.type.body, color = colors.inkFaint)
                field()
            }
        },
    )
}
