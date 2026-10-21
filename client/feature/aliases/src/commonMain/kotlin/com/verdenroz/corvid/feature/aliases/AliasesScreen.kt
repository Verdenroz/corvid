package com.verdenroz.corvid.feature.aliases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.verdenroz.corvid.feature.aliases.resources.aliases_copy
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
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_off_question
import com.verdenroz.corvid.feature.aliases.resources.aliases_turn_on
import com.verdenroz.corvid.feature.aliases.resources.aliases_working
import org.jetbrains.compose.resources.stringResource

@Composable
fun AliasesScreen(
    viewModel: AliasesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val gutter = CorvidSpacing.gutter(LocalWideLayout.current)

    Column(modifier = Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        PageHeader(stringResource(Res.string.aliases_title), stringResource(Res.string.aliases_back), onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 24.dp, bottom = 48.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.aliases_detail), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
                Aliases(state, viewModel)
                if (state.list is AliasList.Listed) {
                    MintForm(state, viewModel)
                    StepText(state.step)
                }
            }
        }
    }
}

@Composable
private fun Aliases(
    state: AliasesState,
    viewModel: AliasesViewModel,
) {
    when (val list = state.list) {
        AliasList.Loading -> Text(stringResource(Res.string.aliases_loading), style = CorvidTheme.type.body)
        AliasList.Off -> Text(stringResource(Res.string.aliases_off), style = CorvidTheme.type.body)
        is AliasList.Failed -> {
            Text(list.message.asString(), style = CorvidTheme.type.body)
            PillButton(stringResource(Res.string.aliases_refresh), null, onClick = viewModel::refresh)
        }
        is AliasList.Listed ->
            if (list.aliases.isEmpty()) {
                Text(stringResource(Res.string.aliases_none), style = CorvidTheme.type.body)
            } else {
                val copy = rememberCopyText()
                var copied by remember { mutableStateOf<String?>(null) }
                Column {
                    HorizontalDivider(color = CorvidTheme.colors.line)
                    list.aliases.forEach { alias ->
                        AliasRow(
                            alias,
                            confirming = state.disabling == alias.name,
                            copied = copied == alias.name,
                            onCopy = {
                                copy(alias.address)
                                copied = alias.name
                            },
                            viewModel,
                        )
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
            if (!alias.missing && !confirming) {
                QuietLink(stringResource(if (copied) Res.string.aliases_copied else Res.string.aliases_copy), onClick = onCopy)
                if (alias.enabled) {
                    QuietLink(stringResource(Res.string.aliases_turn_off), onClick = { viewModel.askDisable(alias.name) })
                } else {
                    QuietLink(stringResource(Res.string.aliases_turn_on), onClick = { viewModel.enable(alias.name) })
                }
            }
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

@Composable
private fun meta(alias: Alias): String {
    val added = alias.createdAt ?: return alias.label
    return stringResource(
        Res.string.aliases_row_meta,
        alias.label,
        stringResource(Res.string.aliases_added_on, added.toString().substringBefore('T')),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.aliases_make_heading), style = CorvidTheme.type.dayHeader, color = CorvidTheme.colors.ink)
        CorvidTextField(state.label, viewModel::setLabel, stringResource(Res.string.aliases_label), Modifier.fillMaxWidth())
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
    Text(text, style = CorvidTheme.type.body, color = CorvidTheme.colors.ink)
}
