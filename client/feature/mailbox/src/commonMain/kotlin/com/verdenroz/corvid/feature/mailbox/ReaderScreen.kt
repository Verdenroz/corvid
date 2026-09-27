package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

@Composable
fun ReaderScreen(
    repository: MailboxRepository,
    emailId: String,
    onBack: () -> Unit,
) {
    val viewModel = remember(repository, emailId) { ReaderViewModel(repository, emailId) }
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("Back") }
        when (val current = state) {
            is ReaderUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ReaderUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(current.message) }
            is ReaderUiState.Gone ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This message no longer exists.") }
            is ReaderUiState.Loaded -> ReaderBody(current)
        }
    }
}

@Composable
private fun ReaderBody(state: ReaderUiState.Loaded) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(state.subject.ifEmpty { "(no subject)" }, style = MaterialTheme.typography.titleLarge)
        Text("From: ${state.from}", style = MaterialTheme.typography.bodyMedium)
        if (state.to.isNotEmpty()) Text("To: ${state.to.joinToString()}", style = MaterialTheme.typography.bodyMedium)
        if (state.cc.isNotEmpty()) Text("Cc: ${state.cc.joinToString()}", style = MaterialTheme.typography.bodyMedium)
        if (state.bodyTruncated) {
            Text(
                "This message was truncated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(state.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
    }
}
