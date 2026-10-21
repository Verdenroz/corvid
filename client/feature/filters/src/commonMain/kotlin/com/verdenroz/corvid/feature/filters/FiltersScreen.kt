package com.verdenroz.corvid.feature.filters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.CorvidIcons
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.designsystem.focusRing
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.ConfirmReplace
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.GuardBack
import com.verdenroz.corvid.core.ui.LoadingPane
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.QuietPane
import com.verdenroz.corvid.core.ui.StatusLine
import com.verdenroz.corvid.feature.filters.resources.Res
import com.verdenroz.corvid.feature.filters.resources.filters_add_rule
import com.verdenroz.corvid.feature.filters.resources.filters_back
import com.verdenroz.corvid.feature.filters.resources.filters_delete
import com.verdenroz.corvid.feature.filters.resources.filters_delete_named
import com.verdenroz.corvid.feature.filters.resources.filters_delete_question
import com.verdenroz.corvid.feature.filters.resources.filters_discard_question
import com.verdenroz.corvid.feature.filters.resources.filters_down
import com.verdenroz.corvid.feature.filters.resources.filters_down_named
import com.verdenroz.corvid.feature.filters.resources.filters_edit
import com.verdenroz.corvid.feature.filters.resources.filters_edit_named
import com.verdenroz.corvid.feature.filters.resources.filters_empty
import com.verdenroz.corvid.feature.filters.resources.filters_foreign_active
import com.verdenroz.corvid.feature.filters.resources.filters_intro
import com.verdenroz.corvid.feature.filters.resources.filters_keep
import com.verdenroz.corvid.feature.filters.resources.filters_title
import com.verdenroz.corvid.feature.filters.resources.filters_up
import com.verdenroz.corvid.feature.filters.resources.filters_up_named
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** [model] gives the back-stack entry's view model, made on first call from the rule being edited before the screen was recreated. */
@Composable
fun FiltersScreen(
    onBack: () -> Unit,
    model: (savedDraft: RuleDraft?) -> FiltersViewModel,
) {
    var savedDraft by rememberSaveable(stateSaver = RuleDraftSaver) { mutableStateOf<RuleDraft?>(null) }
    val viewModel = model(savedDraft)
    val state by viewModel.state.collectAsState()
    (state as? FiltersUiState.Ready)?.let { ready -> if (ready.editing != savedDraft) SideEffect { savedDraft = ready.editing } }
    GuardBack(onBack = viewModel::back)

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(
            stringResource(Res.string.filters_title),
            stringResource(Res.string.filters_back),
        ) { if (!viewModel.back()) onBack() }
        when (val current = state) {
            is FiltersUiState.Loading -> LoadingPane()
            is FiltersUiState.Failed ->
                QuietPane(current.message.asString(), onRetry = viewModel::retry)
            is FiltersUiState.Ready -> {
                val draft = current.editing
                if (current.save is SaveStatus.Confirming) {
                    ConfirmReplace(
                        scriptName = current.foreignScript.orEmpty(),
                        onConfirm = viewModel::confirmReplace,
                        onDismiss = viewModel::dismissConfirmation,
                    )
                }
                if (draft == null) {
                    current.confirmingDelete?.takeIf { it in current.rules }?.let { rule ->
                        DiscardQuestion(
                            stringResource(Res.string.filters_delete_question, rule.name),
                            viewModel::confirmDelete,
                            viewModel::keepRule,
                            confirmLabel = stringResource(Res.string.filters_delete),
                            cancelLabel = stringResource(Res.string.filters_keep),
                            confirmEnabled = current.save !is SaveStatus.Saving,
                        )
                    }
                    RuleList(
                        state = current,
                        onAdd = { viewModel.startEdit(null) },
                        onEdit = viewModel::startEdit,
                        onToggle = viewModel::toggle,
                        onMove = viewModel::move,
                        onDelete = viewModel::delete,
                        onAccept = viewModel::acceptSuggestion,
                        onDismissSuggestion = viewModel::dismissSuggestion,
                    )
                } else {
                    if (current.confirmingDiscard) {
                        DiscardQuestion(stringResource(Res.string.filters_discard_question), viewModel::cancelEdit, viewModel::keepEditing)
                    }
                    RuleEditor(
                        rule = draft.rule,
                        folders = current.folders,
                        onChange = viewModel::changeDraft,
                        onSave = viewModel::saveDraft,
                        onCancel = viewModel::cancelEdit,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleList(
    state: FiltersUiState.Ready,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onMove: (index: Int, offset: Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAccept: (RuleSuggestion) -> Unit,
    onDismissSuggestion: (RuleSuggestion) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current), vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(Res.string.filters_intro), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
                state.foreignScript?.let {
                    Text(
                        stringResource(Res.string.filters_foreign_active, it),
                        style = CorvidTheme.type.meta,
                        color = CorvidTheme.colors.danger,
                    )
                }
                (state.save as? SaveStatus.Failed)?.let {
                    StatusLine(it.message.asString(), failed = true, style = CorvidTheme.type.meta)
                }
                PrimaryButton(
                    stringResource(Res.string.filters_add_rule),
                    onClick = onAdd,
                    icon = CorvidIcons.Filter,
                    enabled = state.save !is SaveStatus.Saving,
                )
            }
            HorizontalDivider(color = CorvidTheme.colors.line)
        }
        suggestionCards(state, onAccept, onDismissSuggestion)
        if (state.rules.isEmpty()) {
            item {
                Text(
                    stringResource(Res.string.filters_empty),
                    style = CorvidTheme.type.body,
                    color = CorvidTheme.colors.inkMuted,
                    modifier =
                        Modifier.padding(
                            horizontal = CorvidSpacing.gutter(LocalWideLayout.current),
                            vertical = 16.dp,
                        ).widthIn(max = 520.dp),
                )
            }
        }
        itemsIndexed(state.rules) { index, rule ->
            RuleRow(
                rule = rule,
                first = index == 0,
                last = index == state.rules.lastIndex,
                enabledControls = state.save !is SaveStatus.Saving,
                onToggle = { onToggle(index) },
                onEdit = { onEdit(index) },
                onUp = { onMove(index, -1) },
                onDown = { onMove(index, 1) },
                onDelete = { onDelete(index) },
            )
            HorizontalDivider(color = CorvidTheme.colors.line)
        }
    }
}

/** While a save runs every control stays in place, disabled, so the list does not jump under the finger. */
@Composable
private fun RuleRow(
    rule: FilterRule,
    first: Boolean,
    last: Boolean,
    enabledControls: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current), vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .focusRing(CorvidShape.small)
                    .toggleable(rule.enabled, enabled = enabledControls, role = Role.Switch) { onToggle() },
        ) {
            Switch(rule.enabled, onCheckedChange = null, Modifier.minimumInteractiveComponentSize(), enabled = enabledControls)
            Text(rule.name, style = CorvidTheme.type.name, fontWeight = FontWeight.SemiBold)
        }
        Text(rule.summary(), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RuleAction(Res.string.filters_edit, Res.string.filters_edit_named, rule.name, enabledControls, onEdit)
            RuleAction(Res.string.filters_up, Res.string.filters_up_named, rule.name, enabledControls && !first, onUp)
            RuleAction(Res.string.filters_down, Res.string.filters_down_named, rule.name, enabledControls && !last, onDown)
            RuleAction(Res.string.filters_delete, Res.string.filters_delete_named, rule.name, enabledControls, onDelete)
        }
    }
}

/** A pill that shows [text] and names the rule it acts on to a screen reader, since every row repeats the same four. */
@Composable
private fun RuleAction(
    text: StringResource,
    named: StringResource,
    ruleName: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(named, ruleName)
    PillButton(
        stringResource(text),
        null,
        onClick,
        Modifier.semantics { contentDescription = description },
        height = 28.dp,
        enabled = enabled,
    )
}
