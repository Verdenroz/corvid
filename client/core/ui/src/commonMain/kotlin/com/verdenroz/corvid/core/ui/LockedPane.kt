package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.locked_title
import com.verdenroz.corvid.core.ui.resources.locked_unlock
import org.jetbrains.compose.resources.stringResource

/**
 * Shown whenever the device key is not held, which is the app's default
 * state: nothing else renders until KeyHolder reaches Unlocked. [onUnlock]
 * re-fetches the key rather than asking for a passphrase: M1 holds no local
 * secret, so unlocking is re-proving this device still has server access.
 */
@Composable
fun LockedPane(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.locked_title), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onUnlock) { Text(stringResource(Res.string.locked_unlock)) }
            }
        }
    }
}
