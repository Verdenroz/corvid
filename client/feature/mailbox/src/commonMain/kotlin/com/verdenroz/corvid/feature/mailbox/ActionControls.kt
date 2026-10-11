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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.verdenroz.corvid.feature.mailbox.resources.action_undo
import com.verdenroz.corvid.feature.mailbox.resources.selection_select
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

@Composable
internal fun ConversationActionRow(
    actions: List<ConversationAction>,
    size: Dp = 40.dp,
    onAction: (ConversationAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { action -> ConversationActionIcon(action, { onAction(action) }, size) }
    }
}

@Composable
internal fun DestroyQuestion(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DiscardQuestion(
        stringResource(Res.string.action_destroy_question),
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
internal fun SelectMark(
    selected: Boolean,
    onToggle: () -> Unit,
    size: Dp = 40.dp,
) {
    val colors = CorvidTheme.colors
    val label = stringResource(Res.string.selection_select)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription = label
                    role = Role.Checkbox
                    this.selected = selected
                },
    ) {
        val ring = Modifier.size(22.dp)
        if (selected) {
            Box(ring.background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(CorvidIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(15.dp))
            }
        } else {
            Box(ring.border(1.5.dp, colors.inkFaint, CircleShape))
        }
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
