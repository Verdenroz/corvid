package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.EmailSummary

@Composable
fun InboxScreen(
    repository: MailboxRepository,
    onOpenEmail: (String) -> Unit,
) {
    val viewModel = remember(repository) { InboxViewModel(repository) }
    val state by viewModel.state.collectAsState()

    when (val current = state) {
        is InboxUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is InboxUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(current.message) }
        is InboxUiState.Loaded ->
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(current.emails, key = { it.id }) { email ->
                    EmailRow(email, onClick = { onOpenEmail(email.id) })
                    HorizontalDivider()
                }
                if (current.canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Button(onClick = viewModel::loadMore) { Text("Load more") }
                        }
                    }
                }
            }
    }
}

@Composable
private fun EmailRow(
    email: EmailSummary,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(email.subject.ifEmpty { "(no subject)" }, style = MaterialTheme.typography.titleMedium)
        Text(email.from, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (email.preview.isNotEmpty()) {
            Text(email.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val labels = email.keywords.filter { it.startsWith("corvid-") }
        if (labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { label -> LabelChip(label) }
            }
        }
    }
}

/** corvid-llm- is the cold-start pass's own guess, never a person's or the labeler's
 *  trained verdict (CLAUDE.md, "keyword provenance"): a different container color is
 *  enough to keep that distinction visible without a second UI language for it. */
@Composable
private fun LabelChip(keyword: String) {
    val isModelGuess = keyword.startsWith("corvid-llm-")
    val text = if (isModelGuess) keyword.removePrefix("corvid-llm-") else keyword.removePrefix("corvid-")
    val containerColor = if (isModelGuess) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (isModelGuess) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = containerColor, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
