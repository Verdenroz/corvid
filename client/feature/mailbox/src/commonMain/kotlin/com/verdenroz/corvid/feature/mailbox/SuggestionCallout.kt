package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.RuleSuggestion
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.offer_cancel
import com.verdenroz.corvid.feature.mailbox.resources.offer_not_now
import com.verdenroz.corvid.feature.mailbox.resources.offer_replace_body
import com.verdenroz.corvid.feature.mailbox.resources.offer_replace_confirm
import org.jetbrains.compose.resources.stringResource

internal class OfferActions(
    val accept: (RuleSuggestion) -> Unit,
    val dismiss: (RuleSuggestion) -> Unit,
    val confirmReplace: (RuleSuggestion) -> Unit,
    val declineReplace: () -> Unit,
)

/** What the sender's mail has been getting done to it, offered as a rule; accepting goes through the same path as Filters. */
@Composable
internal fun SuggestionCallout(
    suggestion: RuleSuggestion,
    offer: SuggestionOfferState,
    wide: Boolean,
    actions: OfferActions,
) {
    val colors = CorvidTheme.colors
    val folders = offer.pending.folders
    val confirming = offer.confirming
    val gutter = CorvidSpacing.gutter(wide)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .padding(start = gutter, end = gutter, top = 16.dp)
                .fillMaxWidth()
                .background(colors.accentSoft, CorvidShape.medium)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (confirming == null) {
            val lead = suggestion.lead(folders).asString()
            val detail = suggestion.detail().asString()
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lead) }
                    append(' ')
                    append(detail)
                },
                style = CorvidTheme.type.name,
                color = colors.onAccentSoft,
            )
        } else {
            Text(
                stringResource(Res.string.offer_replace_body, confirming.scriptName),
                style = CorvidTheme.type.name,
                color = colors.onAccentSoft,
            )
        }
        offer.failed?.let { Text(it.asString(), style = CorvidTheme.type.metaStrong, color = colors.onAccentSoft) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val enabled = !offer.working
            if (confirming == null) {
                OfferButton(stringResource(Res.string.offer_not_now), filled = false) { if (enabled) actions.dismiss(suggestion) }
                OfferButton(suggestion.accepts(folders).asString(), filled = true) { if (enabled) actions.accept(suggestion) }
            } else {
                OfferButton(stringResource(Res.string.offer_cancel), filled = false) { if (enabled) actions.declineReplace() }
                OfferButton(stringResource(Res.string.offer_replace_confirm), filled = true) {
                    if (enabled) actions.confirmReplace(confirming.suggestion)
                }
            }
        }
    }
}

@Composable
private fun OfferButton(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CorvidTheme.colors
    val shape = if (filled) Modifier.background(colors.onAccentSoft, CorvidShape.small) else Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            shape
                .height(36.dp)
                .defaultMinSize(minWidth = 36.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = if (filled) 14.dp else 10.dp),
    ) {
        Text(text, style = CorvidTheme.type.action, color = if (filled) colors.accentSoft else colors.onAccentSoft)
    }
}
