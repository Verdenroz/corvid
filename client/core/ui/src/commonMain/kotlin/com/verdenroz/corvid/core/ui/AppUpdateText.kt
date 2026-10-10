package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.data.AppUpdateState
import com.verdenroz.corvid.core.data.release
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.app_update_action
import com.verdenroz.corvid.core.ui.resources.app_update_allow
import com.verdenroz.corvid.core.ui.resources.app_update_available
import com.verdenroz.corvid.core.ui.resources.app_update_failed
import com.verdenroz.corvid.core.ui.resources.app_update_needs_permission
import com.verdenroz.corvid.core.ui.resources.app_update_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The button that acts on this state, or null when there is nothing to act on. */
fun AppUpdateState.actionLabel(): StringResource? =
    when (this) {
        is AppUpdateState.Available -> Res.string.app_update_action
        is AppUpdateState.NeedsPermission -> Res.string.app_update_allow
        is AppUpdateState.Failed -> Res.string.app_update_retry
        else -> null
    }

/** The build on offer, or that its last attempt failed; null when the state names none. */
@Composable
fun AppUpdateState.offerText(): String? {
    val release = release() ?: return null
    return if (this is AppUpdateState.Failed) {
        stringResource(Res.string.app_update_failed)
    } else {
        stringResource(Res.string.app_update_available, release.versionName)
    }
}

/** Why the offer cannot be taken as is, or null when it can. */
@Composable
fun AppUpdateState.problemText(): String? =
    when (this) {
        is AppUpdateState.NeedsPermission -> stringResource(Res.string.app_update_needs_permission, release.versionName)
        is AppUpdateState.Failed -> message.asString()
        else -> null
    }
