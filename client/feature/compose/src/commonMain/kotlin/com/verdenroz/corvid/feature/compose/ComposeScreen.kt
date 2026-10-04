package com.verdenroz.corvid.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.model.ComposeIntent

@Composable
fun ComposeScreen(
    composeRepository: ComposeRepository,
    intent: ComposeIntent,
    onClose: () -> Unit,
) {
    val viewModel = remember(composeRepository, intent) { ComposeViewModel(composeRepository, intent) }
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            Button(onClick = onClose) { Text("Back") }
            (state as? ComposeUiState.Editing)?.let { editing ->
                Button(onClick = viewModel::send, enabled = !editing.sending) { Text("Send") }
            }
        }
        when (val current = state) {
            is ComposeUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ComposeUiState.Sent -> LaunchedEffect(Unit) { onClose() }
            is ComposeUiState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(current.message) }
            is ComposeUiState.Editing -> ComposeFields(current, onEdit = viewModel::edit)
        }
    }
}

@Composable
private fun ComposeFields(
    state: ComposeUiState.Editing,
    onEdit: (ComposeForm.() -> ComposeForm) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Field("To", state.form.to) { onEdit { copy(to = it) } }
        Field("Cc", state.form.cc) { onEdit { copy(cc = it) } }
        Field("Bcc", state.form.bcc) { onEdit { copy(bcc = it) } }
        Field("Subject", state.form.subject) { onEdit { copy(subject = it) } }
        OutlinedTextField(
            value = state.form.body,
            onValueChange = { onEdit { copy(body = it) } },
            label = { Text("Message") },
            minLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
