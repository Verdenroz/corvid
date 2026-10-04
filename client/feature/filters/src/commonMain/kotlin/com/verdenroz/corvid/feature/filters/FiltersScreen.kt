package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.model.FilterRule

@Composable
fun FiltersScreen(
    repository: FiltersRepository,
    onBack: () -> Unit,
) {
    val viewModel = remember(repository) { FiltersViewModel(repository) }
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("Back") }
        when (val current = state) {
            is FiltersUiState.Loading -> Centered { CircularProgressIndicator() }
            is FiltersUiState.Failed -> Centered { Text(current.message) }
            is FiltersUiState.Ready -> {
                val draft = current.editing
                if (draft == null) {
                    RuleList(
                        state = current,
                        onAdd = { viewModel.startEdit(null) },
                        onEdit = viewModel::startEdit,
                        onToggle = viewModel::toggle,
                        onMove = viewModel::move,
                        onDelete = viewModel::delete,
                    )
                } else {
                    RuleEditor(
                        rule = draft.rule,
                        folders = current.folders,
                        onChange = viewModel::changeDraft,
                        onSave = viewModel::saveDraft,
                        onCancel = viewModel::cancelEdit,
                    )
                }
                if (current.save is SaveStatus.Confirming) {
                    ConfirmReplaceDialog(
                        scriptName = current.foreignScript.orEmpty(),
                        onConfirm = viewModel::confirmReplace,
                        onDismiss = viewModel::dismissConfirmation,
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun RuleList(
    state: FiltersUiState.Ready,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onMove: (index: Int, offset: Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Filters run on the server as mail arrives, in this order.", style = MaterialTheme.typography.bodyMedium)
                state.foreignScript?.let {
                    Text(
                        "Another Sieve script, \"$it\", is active. Saving asks before replacing it.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                (state.save as? SaveStatus.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onAdd, enabled = state.save !is SaveStatus.Saving) { Text("Add rule") }
            }
        }
        itemsIndexed(state.rules) { index, rule ->
            RuleRow(
                rule = rule,
                enabledControls = state.save !is SaveStatus.Saving,
                onToggle = { onToggle(index) },
                onEdit = { onEdit(index) },
                onUp = { onMove(index, -1) },
                onDown = { onMove(index, 1) },
                onDelete = { onDelete(index) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RuleRow(
    rule: FilterRule,
    enabledControls: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() }, enabled = enabledControls)
            Text(rule.name, style = MaterialTheme.typography.titleMedium)
        }
        Text(rule.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = onEdit, enabled = enabledControls) { Text("Edit") }
            TextButton(onClick = onUp, enabled = enabledControls) { Text("Up") }
            TextButton(onClick = onDown, enabled = enabledControls) { Text("Down") }
            TextButton(onClick = onDelete, enabled = enabledControls) { Text("Delete") }
        }
    }
}

@Composable
private fun ConfirmReplaceDialog(
    scriptName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace the active script?") },
        text = { Text("\"$scriptName\" is the active Sieve script. Activating Corvid's filters turns it off. It is not deleted.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Activate Corvid filters") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
