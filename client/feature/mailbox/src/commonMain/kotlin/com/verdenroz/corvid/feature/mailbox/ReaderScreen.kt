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
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.Correction
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.HandleKeys
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_back
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_gone
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_no_subject
import com.verdenroz.corvid.feature.mailbox.resources.reader_newer
import com.verdenroz.corvid.feature.mailbox.resources.reader_older
import com.verdenroz.corvid.feature.mailbox.resources.reader_position
import org.jetbrains.compose.resources.stringResource

/**
 * [neighbours] places the thread in the list it was opened from, null when it came from anywhere else; [onOpen] swaps
 * the reader for another conversation of that list.
 */
@Composable
fun ReaderScreen(
    repository: MailboxRepository,
    files: AttachmentFiles,
    actions: MailActions,
    emailId: String,
    neighbours: Neighbours?,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val viewModel = remember(repository, emailId) { ReaderViewModel(repository, emailId) }
    val state by viewModel.state.collectAsState()
    val onToggle = remember(viewModel) { viewModel::toggle }
    val onAnswer = remember(viewModel) { viewModel::answer }
    val onHeaders = remember(viewModel) { viewModel::toggleHeaders }
    val scope = rememberCoroutineScope()
    val downloads = remember(repository, files) { AttachmentDownloads(repository, files, scope) }
    val attachments by downloads.states.collectAsState()
    val onOpenAttachment = remember(downloads) { downloads::open }

    val wide = LocalWideLayout.current
    val thread = state as? ReaderUiState.Loaded
    val offered = thread?.let { actionsFor(it.kind, it.target()) }.orEmpty()
    var confirming by remember(thread?.newest?.id) { mutableStateOf(false) }
    val perform = { action: ConversationAction ->
        if (thread != null) {
            val exit = exitAfter(action, neighbours)
            actions.perform(action, thread.target())
            when (exit) {
                ReaderExit.Stay -> Unit
                ReaderExit.Back -> onBack()
                is ReaderExit.Open -> onOpen(exit.emailId)
            }
        }
    }
    val onAction = { action: ConversationAction -> if (action == ConversationAction.Destroy) confirming = true else perform(action) }
    val onMove = { folder: MoveFolder ->
        if (thread != null) {
            val exit = exitAfter(ConversationAction.Trash, neighbours)
            actions.moveTo(thread.target().all, 1, folder)
            if (exit is ReaderExit.Open) onOpen(exit.emailId) else onBack()
        }
    }

    HandleKeys { event ->
        val command =
            readerKey(event.key, event.isShiftPressed, event.isCtrlPressed || event.isAltPressed || event.isMetaPressed)
                ?.takeIf { event.type == KeyEventType.KeyDown }
        command?.let { ReaderCommands(thread, offered, neighbours, onAction, onOpen, onBack, onCompose).run(it) }
        command != null
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ActionBar(wide, offered, neighbours, rememberMoveMenu(actions), onMove, onAction, onOpen, onBack)
            if (confirming) {
                DestroyQuestion(onConfirm = { perform(ConversationAction.Destroy) }, onCancel = { confirming = false })
            }
            if (wide) HorizontalDivider(color = CorvidTheme.colors.line)
            when (val current = state) {
                is ReaderUiState.Loading -> Unit
                is ReaderUiState.Failed -> QuietPane(current.message.asString())
                is ReaderUiState.Gone -> QuietPane(stringResource(Res.string.mailbox_gone))
                is ReaderUiState.Loaded ->
                    ThreadColumn(
                        current,
                        repository,
                        wide,
                        attachments,
                        onToggle,
                        onAnswer,
                        onHeaders,
                        onOpenAttachment,
                        onCompose,
                        onOpenSender,
                    )
            }
        }
    }
}

private class ReaderCommands(
    private val thread: ReaderUiState.Loaded?,
    private val offered: List<ConversationAction>,
    private val neighbours: Neighbours?,
    private val onAction: (ConversationAction) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onCompose: (ComposeIntent) -> Unit,
) {
    fun run(command: ReaderKey) {
        val newest = thread?.newest?.id
        when (command) {
            ReaderKey.Reply -> newest?.let { onCompose(ComposeIntent.Reply(it, all = false)) }
            ReaderKey.ReplyAll -> newest?.let { onCompose(ComposeIntent.Reply(it, all = true)) }
            ReaderKey.Forward -> newest?.let { onCompose(ComposeIntent.Forward(it)) }
            ReaderKey.Older -> neighbours?.older?.let(onOpen)
            ReaderKey.Newer -> neighbours?.newer?.let(onOpen)
            ReaderKey.Back -> onBack()
            else -> command.action(offered)?.let(onAction)
        }
    }
}

/** Touch targets stay 48dp when narrow; wide, the pointer gets the denser 40dp. */
@Composable
private fun ActionBar(
    wide: Boolean,
    offered: List<ConversationAction>,
    neighbours: Neighbours?,
    menu: MoveMenu,
    onMove: (MoveFolder) -> Unit,
    onAction: (ConversationAction) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val target = if (wide) 40.dp else 48.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 8.dp),
    ) {
        ActionIcon(stringResource(Res.string.mailbox_back), CorvidIcons.Back, onBack, size = 48.dp)
        Spacer(Modifier.weight(1f))
        ConversationActionRow(offered, menu, onMove, target, shown = if (wide) Int.MAX_VALUE else NARROW_SHOWN, onAction = onAction)
        neighbours?.let {
            if (wide && offered.isNotEmpty()) {
                VerticalDivider(Modifier.padding(horizontal = 8.dp).height(20.dp), color = CorvidTheme.colors.line)
            }
            NeighbourControls(it, wide, target, onOpen)
        }
    }
}

/** Newer is up and older down, the way the list runs; the count shows only once the whole list is loaded. */
@Composable
private fun NeighbourControls(
    neighbours: Neighbours,
    wide: Boolean,
    size: Dp,
    onOpen: (String) -> Unit,
) {
    val total = neighbours.total
    if (wide && total != null) {
        Text(
            stringResource(Res.string.reader_position, neighbours.position, total),
            style = CorvidTheme.type.meta,
            color = CorvidTheme.colors.inkMuted,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
    val newer = neighbours.newer
    val older = neighbours.older
    ActionIcon(stringResource(Res.string.reader_newer), CorvidIcons.ChevronUp, { newer?.let(onOpen) }, size = size, enabled = newer != null)
    ActionIcon(
        stringResource(Res.string.reader_older),
        CorvidIcons.ChevronDown,
        { older?.let(onOpen) },
        size = size,
        enabled = older != null,
    )
}

@Composable
private fun ThreadColumn(
    state: ReaderUiState.Loaded,
    repository: MailboxRepository,
    wide: Boolean,
    attachments: Map<String, AttachmentState>,
    onToggle: (String) -> Unit,
    onAnswer: (String, Correction) -> Unit,
    onHeaders: (String) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onCompose: (ComposeIntent) -> Unit,
    onOpenSender: (String) -> Unit,
) {
    val gutter = CorvidSpacing.readingGutter(wide)
    val threaded = state.messages.size > 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            state.subject.ifEmpty { stringResource(Res.string.mailbox_no_subject) },
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
                headers = state.headers[email.id],
                attachments = attachments,
                onToggle = onToggle,
                onAnswer = onAnswer,
                onHeaders = onHeaders,
                onOpenAttachment = onOpenAttachment,
                onCompose = onCompose,
                onOpenSender = onOpenSender,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}
