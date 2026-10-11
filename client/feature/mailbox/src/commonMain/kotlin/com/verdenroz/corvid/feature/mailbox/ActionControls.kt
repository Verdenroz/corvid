package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.action_destroy_confirm
import com.verdenroz.corvid.feature.mailbox.resources.action_destroy_question
import com.verdenroz.corvid.feature.mailbox.resources.action_dismiss
import com.verdenroz.corvid.feature.mailbox.resources.action_keep
import com.verdenroz.corvid.feature.mailbox.resources.action_more
import com.verdenroz.corvid.feature.mailbox.resources.action_move_loading
import com.verdenroz.corvid.feature.mailbox.resources.action_move_to
import com.verdenroz.corvid.feature.mailbox.resources.action_undo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

private val NOTICE_LIFETIME = 8.seconds

/** An icon button that names itself on hover and to a screen reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionIcon(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = CorvidTheme.colors.inkMuted,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick, Modifier.size(size), enabled = enabled) {
            Icon(icon, label, tint = if (enabled) tint else CorvidTheme.colors.inkFaint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ConversationActionIcon(
    action: ConversationAction,
    onClick: () -> Unit,
    size: Dp,
) {
    val colors = CorvidTheme.colors
    val tint =
        when (action) {
            ConversationAction.Unstar -> colors.accent
            ConversationAction.Destroy -> colors.danger
            else -> colors.inkMuted
        }
    ActionIcon(stringResource(action.label), action.icon, onClick, tint, size)
}

/** Move to's folders, null until the server has answered, and how to ask it again. */
internal class MoveMenu(val folders: List<MoveFolder>?, val onOpen: () -> Unit)

@Composable
internal fun rememberMoveMenu(actions: MailActions): MoveMenu {
    val folders by actions.folders.collectAsState()
    return remember(actions, folders) { MoveMenu(folders, actions::loadFolders) }
}

/** How many action buttons a narrow bar shows before More. */
internal const val NARROW_SHOWN = 2

/** Shows the first [shown] buttons, Move to counted among them; the rest wait under More, for a bar too narrow to hold them all. */
@Composable
internal fun ConversationActionRow(
    actions: List<ConversationAction>,
    menu: MoveMenu,
    onMove: (MoveFolder) -> Unit,
    size: Dp = 40.dp,
    shown: Int = Int.MAX_VALUE,
    onMenuShown: (Boolean) -> Unit = {},
    onAction: (ConversationAction) -> Unit,
) {
    val (moves, marks) = actions.partition { it.moves }
    val buttons: List<ConversationAction?> = moves + null + marks
    Row(verticalAlignment = Alignment.CenterVertically) {
        buttons.take(shown).forEach { action ->
            if (action == null) {
                MoveToButton(
                    menu,
                    onMove,
                    size,
                    onMenuShown,
                )
            } else {
                ConversationActionIcon(action, { onAction(action) }, size)
            }
        }
        val rest = buttons.drop(shown)
        if (rest.isNotEmpty()) MoreButton(rest, menu, onMove, size, onMenuShown, onAction)
    }
}

/** A null entry in [rest] is Move to, which opens the folder list in place. */
@Composable
private fun MoreButton(
    rest: List<ConversationAction?>,
    menu: MoveMenu,
    onMove: (MoveFolder) -> Unit,
    size: Dp,
    onShown: (Boolean) -> Unit,
    onAction: (ConversationAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    val show = { shown: Boolean ->
        expanded = shown
        if (!shown) folders = false
        onShown(shown)
    }
    Box {
        ActionIcon(stringResource(Res.string.action_more), CorvidIcons.More, { show(true) }, size = size)
        DropdownMenu(expanded = expanded, onDismissRequest = { show(false) }) {
            if (folders) {
                FolderItems(menu) { folder ->
                    show(false)
                    onMove(folder)
                }
            } else {
                rest.forEach { action ->
                    val label = stringResource(action?.label ?: Res.string.action_move_to)
                    DropdownMenuItem(
                        text = { Text(label, style = CorvidTheme.type.name) },
                        leadingIcon = { Icon(action?.icon ?: CorvidIcons.Folder, null, tint = CorvidTheme.colors.inkMuted) },
                        onClick = {
                            if (action == null) {
                                menu.onOpen()
                                folders = true
                            } else {
                                show(false)
                                onAction(action)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderItems(
    menu: MoveMenu,
    onPick: (MoveFolder) -> Unit,
) {
    val folders = menu.folders
    if (folders == null) {
        DropdownMenuItem(text = { Text(stringResource(Res.string.action_move_loading)) }, onClick = {}, enabled = false)
    } else {
        folders.forEach { folder ->
            DropdownMenuItem(text = { Text(folder.label, style = CorvidTheme.type.name) }, onClick = { onPick(folder) })
        }
    }
}

@Composable
private fun MoveToButton(
    menu: MoveMenu,
    onMove: (MoveFolder) -> Unit,
    size: Dp,
    onShown: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val show = { shown: Boolean ->
        expanded = shown
        onShown(shown)
    }
    Box {
        ActionIcon(stringResource(Res.string.action_move_to), CorvidIcons.Folder, {
            menu.onOpen()
            show(true)
        }, size = size)
        DropdownMenu(expanded = expanded, onDismissRequest = { show(false) }) {
            FolderItems(menu) { folder ->
                show(false)
                onMove(folder)
            }
        }
    }
}

@Composable
internal fun DestroyQuestion(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    question: String = stringResource(Res.string.action_destroy_question),
) {
    DiscardQuestion(
        question,
        onConfirm,
        onCancel,
        confirmLabel = stringResource(Res.string.action_destroy_confirm),
        cancelLabel = stringResource(Res.string.action_keep),
    )
}

@Composable
internal fun StarMark(modifier: Modifier = Modifier) {
    Icon(CorvidIcons.Star, null, tint = CorvidTheme.colors.accent, modifier = modifier.size(15.dp))
}

/** Takes the avatar's place while a row can be ticked: an empty ring, or filled with a check once ticked. */
@Composable
internal fun TickRing(selected: Boolean) {
    val colors = CorvidTheme.colors
    val ring = Modifier.size(22.dp)
    if (selected) {
        Box(ring.background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(CorvidIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(15.dp))
        }
    } else {
        Box(ring.border(1.5.dp, colors.inkFaint, CircleShape))
    }
}

/** What the last action did, with Undo while it can still be put back; it leaves on its own. */
@Composable
fun ActionNoticeBar(
    actions: MailActions,
    notice: ActionNotice,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(notice) {
        delay(NOTICE_LIFETIME)
        actions.dismiss()
    }
    val colors = CorvidTheme.colors
    Surface(shape = CorvidShape.medium, color = colors.ink, contentColor = colors.surface, shadowElevation = 6.dp, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.widthIn(min = 280.dp, max = 520.dp).padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                notice.text.asString(),
                style = CorvidTheme.type.name,
                color = colors.surface,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            )
            notice.undo?.let {
                Text(
                    stringResource(Res.string.action_undo),
                    style = CorvidTheme.type.action,
                    fontWeight = FontWeight.Bold,
                    color = colors.surface,
                    modifier =
                        Modifier.clip(
                            CorvidShape.small,
                        ).clickable(onClick = actions::undo).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            ActionIcon(stringResource(Res.string.action_dismiss), CorvidIcons.Close, actions::dismiss, tint = colors.surface)
        }
    }
}
