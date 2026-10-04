package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.EmailDetail

@Composable
fun ReaderScreen(
    repository: MailboxRepository,
    emailId: String,
    onBack: () -> Unit,
    onCompose: (ComposeIntent) -> Unit,
) {
    val viewModel = remember(repository, emailId) { ReaderViewModel(repository, emailId) }
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            Button(onClick = onBack) { Text("Back") }
            (state as? ReaderUiState.Loaded)?.email?.let { email ->
                Button(onClick = { onCompose(ComposeIntent.Reply(email, all = false)) }) { Text("Reply") }
                Button(onClick = { onCompose(ComposeIntent.Reply(email, all = true)) }) { Text("Reply all") }
                Button(onClick = { onCompose(ComposeIntent.Forward(email)) }) { Text("Forward") }
            }
        }
        when (val current = state) {
            is ReaderUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ReaderUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(current.message) }
            is ReaderUiState.Gone ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This message no longer exists.") }
            is ReaderUiState.Loaded -> ReaderBody(current.email)
        }
    }
}

@Composable
private fun ReaderBody(email: EmailDetail) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(email.subject.ifEmpty { "(no subject)" }, style = MaterialTheme.typography.titleLarge)
        Text("From: ${email.from?.toText().orEmpty()}", style = MaterialTheme.typography.bodyMedium)
        if (email.to.isNotEmpty()) Text("To: ${email.to.joinToString { it.toText() }}", style = MaterialTheme.typography.bodyMedium)
        if (email.cc.isNotEmpty()) Text("Cc: ${email.cc.joinToString { it.toText() }}", style = MaterialTheme.typography.bodyMedium)
        if (email.bodyTruncated) {
            Text(
                "This message was truncated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(email.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
    }
}
