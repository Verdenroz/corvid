package com.verdenroz.corvid.feature.aliases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.Alias
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.PageHeader
import com.verdenroz.corvid.core.ui.rememberCopyText
import com.verdenroz.corvid.feature.aliases.resources.Res
import com.verdenroz.corvid.feature.aliases.resources.aliases_added_on
import com.verdenroz.corvid.feature.aliases.resources.aliases_back
import com.verdenroz.corvid.feature.aliases.resources.aliases_copied
import com.verdenroz.corvid.feature.aliases.resources.aliases_copied_named
import com.verdenroz.corvid.feature.aliases.resources.aliases_copy
import com.verdenroz.corvid.feature.aliases.resources.aliases_copy_named
import com.verdenroz.corvid.feature.aliases.resources.aliases_detail
import com.verdenroz.corvid.feature.aliases.resources.aliases_is_off
import com.verdenroz.corvid.feature.aliases.resources.aliases_keep_on
import com.verdenroz.corvid.feature.aliases.resources.aliases_label
import com.verdenroz.corvid.feature.aliases.resources.aliases_label_hint
import com.verdenroz.corvid.feature.aliases.resources.aliases_loading
import com.verdenroz.corvid.feature.aliases.resources.aliases_make
import com.verdenroz.corvid.feature.aliases.resources.aliases_make_heading
import com.verdenroz.corvid.feature.aliases.resources.aliases_minted
import com.verdenroz.corvid.feature.aliases.resources.aliases_minting
import com.verdenroz.corvid.feature.aliases.resources.aliases_missing
import com.verdenroz.corvid.feature.aliases.resources.aliases_none
import com.verdenroz.corvid.feature.aliases.resources.aliases_off
import com.verdenroz.corvid.feature.aliases.resources.aliases_refresh
import com.verdenroz.corvid.feature.aliases.resources.aliases_row_meta
import com.verdenroz.corvid.feature.aliases.resources.aliases_signing_in
import com.verdenroz.corvid.feature.aliases.resources.aliases_switched_off
import com.verdenroz.corvid.feature.aliases.resources.aliases_switched_on
import com.verdenroz.corvid.feature.aliases.resources.aliases_title
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_off
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_off_named
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_off_question
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_on
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_on_named
import com.verdenroz.corvid.feature.aliases.resources.aliases_working
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

private val PAGE_WIDTH = 640.dp

@Composable
fun AliasesScreen(
    viewModel: AliasesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)
    val copy = rememberCopyText()
    var copied by remember { mutableStateOf<String?>(null) }
    var copies by remember { mutableIntStateOf(0) }
    LaunchedEffect(copied, copies) {
        if (copied != null) {
            delay(2.seconds)
            copied = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.aliases_title), stringResource(Res.string.aliases_back), onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    stringResource(Res.string.aliases_detail),
                    style = CorvidTheme.type.body,
                    color = CorvidTheme.colors.inkMuted,
                    modifier = Modifier.widthIn(max = PAGE_WIDTH),
                )
            }
            if (state.list is AliasList.Listed) {
                item { MintForm(state, viewModel) }
                item { StepText(state.step) }
            }
            aliases(state, viewModel, copied) { alias ->
                copy(alias.address)
                copied = alias.name
                copies++
            }
        }
    }
}

private fun LazyListScope.aliases(
    state: AliasesState,
    viewModel: AliasesViewModel,
    copied: String?,
    onCopy: (Alias) -> Unit,
) {
    when (val list = state.list) {
        AliasList.Loading ->
            item {
                Text(
                    stringResource(Res.string.aliases_loading),
                    style = CorvidTheme.type.body,
                    modifier = Modifier.widthIn(max = PAGE_WIDTH),
                )
            }
        AliasList.Off ->
            item {
                Text(stringResource(Res.string.aliases_off), style = CorvidTheme.type.body, modifier = Modifier.widthIn(max = PAGE_WIDTH))
            }
        is AliasList.Failed ->
            item {
                Column(Modifier.widthIn(max = PAGE_WIDTH), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(list.message.asString(), style = CorvidTheme.type.body)
                    PillButton(stringResource(Res.string.aliases_refresh), null, onClick = viewModel::refresh)
                }
            }
        is AliasList.Listed ->
            if (list.aliases.isEmpty()) {
                item { Text(stringResource(Res.string.aliases_none), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted) }
            } else {
                item { HorizontalDivider(Modifier.widthIn(max = PAGE_WIDTH), color = CorvidTheme.colors.line) }
                items(list.aliases, key = { it.name }) { alias ->
                    Column(Modifier.widthIn(max = PAGE_WIDTH)) {
                        AliasRow(alias, state.disabling == alias.name, copied == alias.name, { onCopy(alias) }, viewModel)
                        HorizontalDivider(color = CorvidTheme.colors.line)
                    }
                }
            }
    }
}

@Composable
private fun AliasRow(
    alias: Alias,
    confirming: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    viewModel: AliasesViewModel,
) {
    val wide = LocalWideLayout.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        alias.address,
                        style = CorvidTheme.type.name,
                        color = if (alias.enabled) CorvidTheme.colors.ink else CorvidTheme.colors.inkMuted,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!alias.enabled && !alias.missing) OffTag()
                }
                Text(meta(alias), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
                if (alias.missing) {
                    Text(stringResource(Res.string.aliases_missing), style = CorvidTheme.type.meta, color = CorvidTheme.colors.ink)
                }
            }
            if (wide && !alias.missing && !confirming) RowActions(alias, copied, onCopy, viewModel)
        }
        if (!wide && !alias.missing && !confirming) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { RowActions(alias, copied, onCopy, viewModel) }
        }
        if (confirming) {
            DiscardQuestion(
                stringResource(Res.string.aliases_turn_off_question),
                { viewModel.disable(alias.name) },
                viewModel::keep,
                confirmLabel = stringResource(Res.string.aliases_turn_off),
                cancelLabel = stringResource(Res.string.aliases_keep_on),
            )
        }
    }
}

/** Every row has the same links, so each names its alias for a screen reader. */
@Composable
private fun RowActions(
    alias: Alias,
    copied: Boolean,
    onCopy: () -> Unit,
    viewModel: AliasesViewModel,
) {
    val copyName = stringResource(if (copied) Res.string.aliases_copied_named else Res.string.aliases_copy_named, alias.address)
    QuietLink(
        stringResource(if (copied) Res.string.aliases_copied else Res.string.aliases_copy),
        onClick = onCopy,
        modifier = Modifier.semantics { contentDescription = copyName },
    )
    if (alias.enabled) {
        val offName = stringResource(Res.string.aliases_turn_off_named, alias.address)
        QuietLink(
            stringResource(Res.string.aliases_turn_off),
            onClick = { viewModel.askDisable(alias.name) },
            modifier = Modifier.semantics { contentDescription = offName },
        )
    } else {
        val onName = stringResource(Res.string.aliases_turn_on_named, alias.address)
        QuietLink(
            stringResource(Res.string.aliases_turn_on),
            onClick = { viewModel.enable(alias.name) },
            modifier = Modifier.semantics { contentDescription = onName },
        )
    }
}

@Composable
private fun meta(alias: Alias): String {
    val added = alias.createdAt ?: return alias.label
    return stringResource(
        Res.string.aliases_row_meta,
        alias.label,
        stringResource(Res.string.aliases_added_on, added.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()),
    )
}

@Composable
private fun OffTag() {
    Text(
        stringResource(Res.string.aliases_is_off),
        style = CorvidTheme.type.chip,
        color = CorvidTheme.colors.inkMuted,
        modifier = Modifier.border(1.dp, CorvidTheme.colors.line, CorvidShape.pill).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun MintForm(
    state: AliasesState,
    viewModel: AliasesViewModel,
) {
    Column(Modifier.widthIn(max = PAGE_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.aliases_make_heading),
            style = CorvidTheme.type.dayHeader,
            color = CorvidTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        CorvidTextField(
            state.label,
            viewModel::setLabel,
            stringResource(Res.string.aliases_label),
            Modifier.fillMaxWidth(),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
        )
        Text(stringResource(Res.string.aliases_label_hint), style = CorvidTheme.type.meta, color = CorvidTheme.colors.inkMuted)
        PrimaryButton(stringResource(Res.string.aliases_make), viewModel::mint, enabled = !state.busy && state.label.isNotEmpty())
    }
}

@Composable
private fun StepText(step: AliasStep) {
    val text =
        when (step) {
            AliasStep.Idle -> return
            AliasStep.Minting -> stringResource(Res.string.aliases_minting)
            AliasStep.SigningIn -> stringResource(Res.string.aliases_signing_in)
            AliasStep.Working -> stringResource(Res.string.aliases_working)
            is AliasStep.Minted -> stringResource(Res.string.aliases_minted, step.alias.address)
            is AliasStep.Switched ->
                stringResource(
                    if (step.alias.enabled) Res.string.aliases_switched_on else Res.string.aliases_switched_off,
                    step.alias.address,
                )
            is AliasStep.Failed -> step.message.asString()
        }
    Text(
        text,
        style = CorvidTheme.type.body,
        color = CorvidTheme.colors.ink,
        modifier = Modifier.widthIn(max = PAGE_WIDTH).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
