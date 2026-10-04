package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.revoked_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when a query that found nothing follows a key this session already
 * held: the file is confirmed gone, not just unreachable, so there is
 * nothing to retry. Reaching this device's access again means enrolling it
 * again from a state that still has access.
 */
@Composable
fun RevokedPane(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(stringResource(Res.string.revoked_title), style = MaterialTheme.typography.titleMedium)
        }
    }
}
