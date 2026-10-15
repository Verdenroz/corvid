package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.ActionIcon
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.text.uiPlural
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.LocalWideLayout
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.overnight_hide
import com.verdenroz.corvid.feature.mailbox.resources.overnight_join
import com.verdenroz.corvid.feature.mailbox.resources.overnight_lists
import com.verdenroz.corvid.feature.mailbox.resources.overnight_people_one
import com.verdenroz.corvid.feature.mailbox.resources.overnight_people_three
import com.verdenroz.corvid.feature.mailbox.resources.overnight_people_two
import com.verdenroz.corvid.feature.mailbox.resources.overnight_title
import org.jetbrains.compose.resources.stringResource

private const val NAMES_SHOWN = 3

/** An unseen digest offered at the top of the Inbox: [count] messages, [lists] of them from lists and the rest from [people]. */
data class OvernightEntry(
    val id: String,
    val count: Int,
    val people: List<String>,
    val lists: Int,
) {
    val line: UiText
        get() {
            val names = people.take(NAMES_SHOWN)
            val who =
                when (names.size) {
                    0 -> null
                    1 -> uiText(Res.string.overnight_people_one, names[0])
                    2 -> uiText(Res.string.overnight_people_two, names[0], names[1])
                    else -> uiText(Res.string.overnight_people_three, names[0], names[1], names[2])
                }
            val fromLists = uiPlural(Res.plurals.overnight_lists, lists)
            return when {
                who == null -> fromLists
                lists == 0 -> who
                else -> uiText(Res.string.overnight_join, who, fromLists)
            }
        }
}

class OvernightOffer(
    val entry: OvernightEntry,
    val onOpen: () -> Unit,
    val onHide: () -> Unit,
)

/** The whole block opens the digest; the × beside it hides it. The names come from mail, so they are plain text. */
@Composable
internal fun OvernightBlock(
    entry: OvernightEntry,
    onOpen: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(start = gutter, end = gutter, top = 12.dp, bottom = 4.dp)
                .fillMaxWidth()
                .clip(CorvidShape.medium)
                .background(colors.rail),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier.weight(
                    1f,
                ).clickable(role = Role.Button, onClick = onOpen).padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(Res.string.overnight_title), style = CorvidTheme.type.dayHeader)
                    Text(entry.count.toString(), style = CorvidTheme.type.meta, color = colors.inkMuted)
                }
                Text(
                    entry.line.asString(),
                    style = CorvidTheme.type.meta,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(CorvidIcons.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(16.dp))
        }
        ActionIcon(
            stringResource(Res.string.overnight_hide),
            CorvidIcons.Close,
            onHide,
            tint = colors.inkFaint,
            size = 44.dp,
            iconSize = 16.dp,
        )
    }
}
