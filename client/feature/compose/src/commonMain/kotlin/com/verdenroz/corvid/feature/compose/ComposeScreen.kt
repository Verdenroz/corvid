package com.verdenroz.corvid.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_bcc
import com.verdenroz.corvid.feature.compose.resources.compose_cc
import com.verdenroz.corvid.feature.compose.resources.compose_close
import com.verdenroz.corvid.feature.compose.resources.compose_discard
import com.verdenroz.corvid.feature.compose.resources.compose_discard_question
import com.verdenroz.corvid.feature.compose.resources.compose_keep_editing
import com.verdenroz.corvid.feature.compose.resources.compose_message
import com.verdenroz.corvid.feature.compose.resources.compose_send
import com.verdenroz.corvid.feature.compose.resources.compose_subject
import com.verdenroz.corvid.feature.compose.resources.compose_title_forward
import com.verdenroz.corvid.feature.compose.resources.compose_title_new
import com.verdenroz.corvid.feature.compose.resources.compose_title_reply
import com.verdenroz.corvid.feature.compose.resources.compose_to
import org.jetbrains.compose.resources.stringResource

@Composable
fun ComposeScreen(
    composeRepository: ComposeRepository,
    intent: ComposeIntent,
    onClose: () -> Unit,
) {
    val viewModel = remember(composeRepository, intent) { ComposeViewModel(composeRepository, intent) }
    val state by viewModel.state.collectAsState()
    val close = { if (viewModel.requestClose()) onClose() }
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true, onBackCompleted = close)

    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        val editing = state as? ComposeUiState.Editing
        Bar(intent, sending = editing?.sending ?: true, onClose = close, onSend = viewModel::send)
        when (val current = state) {
            is ComposeUiState.Loading -> Unit
            is ComposeUiState.Sent -> LaunchedEffect(Unit) { onClose() }
            is ComposeUiState.Failed -> Quiet(current.message.asString())
            is ComposeUiState.Editing -> {
                if (current.confirmingDiscard) DiscardQuestion(onDiscard = onClose, onKeep = viewModel::keepEditing)
                Fields(current, onEdit = viewModel::edit)
            }
        }
    }
}

@Composable
private fun Bar(
    intent: ComposeIntent,
    sending: Boolean,
    onClose: () -> Unit,
    onSend: () -> Unit,
) {
    val title =
        when (intent) {
            is ComposeIntent.Reply -> Res.string.compose_title_reply
            is ComposeIntent.Forward -> Res.string.compose_title_forward
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
        PrimaryButton(stringResource(Res.string.compose_send), onClick = onSend, icon = CorvidIcons.Send, enabled = !sending)
    }
    HorizontalDivider(color = CorvidTheme.colors.line)
}

@Composable
private fun DiscardQuestion(
    onDiscard: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = CorvidTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(colors.dangerSoft).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(Res.string.compose_discard_question),
            style = CorvidTheme.type.metaStrong,
            color = colors.onDangerSoft,
            modifier = Modifier.weight(1f),
        )
        PillButton(stringResource(Res.string.compose_discard), null, onDiscard, height = 30.dp)
        PillButton(stringResource(Res.string.compose_keep_editing), null, onKeep, height = 30.dp)
    }
}

@Composable
private fun Fields(
    state: ComposeUiState.Editing,
    onEdit: (ComposeForm.() -> ComposeForm) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        state.error?.let {
            Text(
                it.asString(),
                style = CorvidTheme.type.meta,
                color = CorvidTheme.colors.danger,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Field(stringResource(Res.string.compose_to), state.form.to) { onEdit { copy(to = it) } }
        Field(stringResource(Res.string.compose_cc), state.form.cc) { onEdit { copy(cc = it) } }
        Field(stringResource(Res.string.compose_bcc), state.form.bcc) { onEdit { copy(bcc = it) } }
        Field(stringResource(Res.string.compose_subject), state.form.subject) { onEdit { copy(subject = it) } }
        Body(state.form.body) { onEdit { copy(body = it) } }
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

@Composable
private fun Quiet(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
    }
}
