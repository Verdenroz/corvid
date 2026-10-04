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
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.unreachable_retry
import com.verdenroz.corvid.core.ui.resources.unreachable_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shown for a network failure, a damaged key file or an ambiguous device
 * folder set. Nothing is wiped for any of the three: only a confirmed
 * Missing lookup (RevokedPane) means the device actually lost access.
 */
@Composable
fun UnreachablePane(
    reason: UiText,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.unreachable_title), style = MaterialTheme.typography.titleMedium)
                Text(reason.asString(), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) { Text(stringResource(Res.string.unreachable_retry)) }
            }
        }
    }
}
