package com.verdenroz.corvid.app.shell

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.app.HomeTab
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.tab_archive
import com.verdenroz.corvid.appcommon.resources.tab_drafts
import com.verdenroz.corvid.appcommon.resources.tab_inbox
import com.verdenroz.corvid.appcommon.resources.tab_junk
import com.verdenroz.corvid.appcommon.resources.tab_labels
import com.verdenroz.corvid.appcommon.resources.tab_people
import com.verdenroz.corvid.appcommon.resources.tab_sent
import com.verdenroz.corvid.appcommon.resources.tab_sources
import com.verdenroz.corvid.appcommon.resources.tab_starred
import com.verdenroz.corvid.appcommon.resources.tab_trash
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import org.jetbrains.compose.resources.StringResource

internal fun HomeTab.title(): StringResource =
    when (this) {
        HomeTab.Inbox -> Res.string.tab_inbox
        HomeTab.Starred -> Res.string.tab_starred
        HomeTab.Sent -> Res.string.tab_sent
        HomeTab.Drafts -> Res.string.tab_drafts
        HomeTab.Archive -> Res.string.tab_archive
        HomeTab.Junk -> Res.string.tab_junk
        HomeTab.Trash -> Res.string.tab_trash
        HomeTab.People -> Res.string.tab_people
        HomeTab.Sources -> Res.string.tab_sources
        HomeTab.Labels -> Res.string.tab_labels
    }

internal fun HomeTab.icon(): ImageVector =
    when (this) {
        HomeTab.Inbox -> CorvidIcons.Inbox
        HomeTab.Starred -> CorvidIcons.Star
        HomeTab.Sent -> CorvidIcons.Send
        HomeTab.Drafts -> CorvidIcons.Draft
        HomeTab.Archive -> CorvidIcons.Archive
        HomeTab.Junk -> CorvidIcons.Spam
        HomeTab.Trash -> CorvidIcons.Trash
        HomeTab.People -> CorvidIcons.Person
        HomeTab.Sources -> CorvidIcons.Box
        HomeTab.Labels -> CorvidIcons.Tag
    }

/** The current place's mark, tabs and sidebar alike: a short accent rule under the item, centred on [centerX]. */
internal fun DrawScope.drawIndicator(
    color: Color,
    centerX: Float = size.width / 2,
    width: Dp = 20.dp,
) {
    val widthPx = width.toPx()
    drawRoundRect(
        color,
        Offset(centerX - widthPx / 2, size.height - 2.dp.toPx()),
        Size(widthPx, 2.dp.toPx()),
        CornerRadius(1.dp.toPx()),
    )
}
