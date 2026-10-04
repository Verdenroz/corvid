package com.verdenroz.corvid.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.locked_detail
import com.verdenroz.corvid.core.ui.resources.locked_device
import com.verdenroz.corvid.core.ui.resources.locked_device_dropped
import com.verdenroz.corvid.core.ui.resources.locked_server
import com.verdenroz.corvid.core.ui.resources.locked_title
import com.verdenroz.corvid.core.ui.resources.locked_unlock
import org.jetbrains.compose.resources.stringResource

/**
 * Shown whenever the device key is not held, which is the app's default
 * state: nothing else renders until KeyHolder reaches Unlocked. [onUnlock]
 * re-fetches the key rather than asking for a passphrase: M1 holds no local
 * secret, so unlocking is re-proving this device still has server access.
 * Each fact is shown only when known.
 */
@Composable
fun LockedPane(
    server: String?,
    device: String?,
    droppedAt: String?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val facts =
        listOfNotNull(
            server?.let { StateFact(stringResource(Res.string.locked_server), it) },
            device?.let { name ->
                StateFact(
                    stringResource(Res.string.locked_device),
                    droppedAt?.let { stringResource(Res.string.locked_device_dropped, name, it) } ?: name,
                )
            },
        )
    StatePane(stringResource(Res.string.locked_title), modifier, detail = stringResource(Res.string.locked_detail), facts = facts) {
        PrimaryButton(stringResource(Res.string.locked_unlock), onUnlock)
    }
}
