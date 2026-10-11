package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Swiping right archives and left deletes, each only where the list offers that move. */
internal data class SwipeActions(val right: ConversationAction?, val left: ConversationAction?)

internal fun swipeActions(kind: ListKind) =
    when (kind) {
        ListKind.Inbox -> SwipeActions(right = ConversationAction.Archive, left = ConversationAction.Trash)
        ListKind.Trash -> SwipeActions(right = null, left = ConversationAction.Destroy)
        ListKind.Other -> SwipeActions(right = null, left = ConversationAction.Trash)
    }

/** A moved row leaves the list on its own; one deleted for good waits on a question, so it slides back. */
@Composable
internal fun SwipeRow(
    kind: ListKind,
    enabled: Boolean,
    onSwipe: (ConversationAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val swipes = swipeActions(kind)
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state,
        backgroundContent = { SwipeBackground(state.dismissDirection, swipes) },
        enableDismissFromStartToEnd = swipes.right != null,
        enableDismissFromEndToStart = swipes.left != null,
        gesturesEnabled = enabled,
        onDismiss = { direction ->
            val action = if (direction == SwipeToDismissBoxValue.StartToEnd) swipes.right else swipes.left
            action?.let(onSwipe)
            if (action == ConversationAction.Destroy) scope.launch { state.reset() }
        },
    ) { content() }
}

@Composable
private fun RowScope.SwipeBackground(
    direction: SwipeToDismissBoxValue,
    swipes: SwipeActions,
) {
    val action =
        when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> swipes.right
            SwipeToDismissBoxValue.EndToStart -> swipes.left
            SwipeToDismissBoxValue.Settled -> null
        } ?: return
    val colors = CorvidTheme.colors
    val archive = action == ConversationAction.Archive
    val fill = if (archive) colors.accentSoft else colors.dangerSoft
    val tint = if (archive) colors.onAccentSoft else colors.onDangerSoft
    val side = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.Start else Alignment.End
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, side),
        modifier = Modifier.fillMaxSize().background(fill).padding(horizontal = 24.dp),
    ) {
        Icon(action.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(stringResource(action.label), style = CorvidTheme.type.action, color = tint)
    }
}
