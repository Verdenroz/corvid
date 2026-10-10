package com.verdenroz.corvid.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.app_update_action
import com.verdenroz.corvid.appcommon.resources.app_update_allow
import com.verdenroz.corvid.appcommon.resources.app_update_available
import com.verdenroz.corvid.appcommon.resources.app_update_needs_permission
import com.verdenroz.corvid.appcommon.resources.app_update_retry
import com.verdenroz.corvid.appcommon.resources.notices_dismiss
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.LocalWideLayout
import org.jetbrains.compose.resources.stringResource

/** One line above the shell offering a newer app build, or saying why the last attempt did not finish. */
@Composable
internal fun AppUpdateBannerBar(
    state: AppUpdateState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val failed = state is AppUpdateState.Failed
    val background = if (failed) colors.dangerSoft else colors.accentSoft
    val content = if (failed) colors.onDangerSoft else colors.onAccentSoft
    val line =
        when (state) {
            is AppUpdateState.Available -> stringResource(Res.string.app_update_available, state.release.versionName)
            is AppUpdateState.NeedsPermission -> stringResource(Res.string.app_update_needs_permission)
            is AppUpdateState.Failed -> state.message.asString()
            else -> return
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .heightIn(min = 44.dp)
                .padding(start = CorvidSpacing.gutter(LocalWideLayout.current)),
    ) {
        Text(
            line,
            style = CorvidTheme.type.meta,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUpdate) {
            Text(
                stringResource(
                    when {
                        failed -> Res.string.app_update_retry
                        state is AppUpdateState.NeedsPermission -> Res.string.app_update_allow
                        else -> Res.string.app_update_action
                    },
                ),
                style = CorvidTheme.type.meta,
                color = content,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(CorvidIcons.Close, stringResource(Res.string.notices_dismiss), tint = content, modifier = Modifier.size(18.dp))
        }
    }
}
