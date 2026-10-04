package com.verdenroz.corvid.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.GuardBack
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_bcc
import com.verdenroz.corvid.feature.compose.resources.compose_cc
import com.verdenroz.corvid.feature.compose.resources.compose_close
import com.verdenroz.corvid.feature.compose.resources.compose_discard_question
import com.verdenroz.corvid.feature.compose.resources.compose_message
import com.verdenroz.corvid.feature.compose.resources.compose_send
import com.verdenroz.corvid.feature.compose.resources.compose_show_copies
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
    var savedForm by rememberSaveable(stateSaver = ComposeFormSaver) { mutableStateOf<ComposeForm?>(null) }
    val viewModel = remember(composeRepository, intent) { ComposeViewModel(composeRepository, intent, savedForm) }
    val state by viewModel.state.collectAsState()
    (state as? ComposeUiState.Editing)?.form?.let { if (it != savedForm) SideEffect { savedForm = it } }
    val close = { if (viewModel.requestClose()) onClose() }
    GuardBack { !viewModel.requestClose() }

    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        val editing = state as? ComposeUiState.Editing
        Bar(intent, sending = editing?.sending ?: true, onClose = close, onSend = viewModel::send)
        when (val current = state) {
            is ComposeUiState.Loading -> Unit
            is ComposeUiState.Sent -> LaunchedEffect(Unit) { onClose() }
            is ComposeUiState.Failed -> QuietPane(current.message.asString())
            is ComposeUiState.Editing -> {
                if (current.confirmingDiscard) {
                    DiscardQuestion(
                        stringResource(Res.string.compose_discard_question),
                        onDiscard = onClose,
                        onKeep = viewModel::keepEditing,
                    )
                }
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
        var copies by rememberSaveable { mutableStateOf(state.form.cc.isNotEmpty() || state.form.bcc.isNotEmpty()) }
        Field(
            stringResource(Res.string.compose_to),
            state.form.to,
            trailing =
                if (copies) {
                    null
                } else {
                    (
                        {
                            copies = true
                        }
                    )
                },
        ) { onEdit { copy(to = it) } }
        if (copies) {
            Field(stringResource(Res.string.compose_cc), state.form.cc) { onEdit { copy(cc = it) } }
            Field(stringResource(Res.string.compose_bcc), state.form.bcc) { onEdit { copy(bcc = it) } }
        }
        Field(stringResource(Res.string.compose_subject), state.form.subject) { onEdit { copy(subject = it) } }
        Body(state.form.body) { onEdit { copy(body = it) } }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    trailing: (() -> Unit)? = null,
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
            trailing?.let {
                Text(
                    stringResource(Res.string.compose_show_copies),
                    style = CorvidTheme.type.meta,
                    color = colors.inkMuted,
                    modifier = Modifier.clickable(onClick = it).padding(8.dp),
                )
            }
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
