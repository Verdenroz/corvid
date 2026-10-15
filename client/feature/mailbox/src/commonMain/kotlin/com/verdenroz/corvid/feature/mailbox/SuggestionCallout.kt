package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.verdenroz.corvid.core.ui.suggestion.SuggestionButton
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.offer_not_now
import org.jetbrains.compose.resources.stringResource

internal class OfferActions(
    val accept: (RuleSuggestion) -> Unit,
    val dismiss: (RuleSuggestion) -> Unit,
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
    val lead = suggestion.lead(offer.folders).asString()
    val detail = suggestion.detail().asString()
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
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lead) }
                append(' ')
                append(detail)
            },
            style = CorvidTheme.type.name,
            color = colors.onAccentSoft,
        )
        offer.failed?.let { Text(it.asString(), style = CorvidTheme.type.metaStrong, color = colors.onAccentSoft) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val enabled = !offer.working
            SuggestionButton(
                stringResource(Res.string.offer_not_now),
                { if (enabled) actions.dismiss(suggestion) },
                ink = colors.onAccentSoft,
            )
            SuggestionButton(
                suggestion.accepts(offer.folders).asString(),
                { if (enabled) actions.accept(suggestion) },
                ink = colors.accentSoft,
                fill = colors.onAccentSoft,
            )
        }
    }
}
