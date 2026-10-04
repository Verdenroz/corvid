package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.monthYear
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.model.labels
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.card_authenticity
import com.verdenroz.corvid.feature.mailbox.resources.card_between
import com.verdenroz.corvid.feature.mailbox.resources.card_between_you
import com.verdenroz.corvid.feature.mailbox.resources.card_filed_as
import com.verdenroz.corvid.feature.mailbox.resources.card_messages_since
import com.verdenroz.corvid.feature.mailbox.resources.card_not_labelled
import com.verdenroz.corvid.feature.mailbox.resources.card_trackers
import com.verdenroz.corvid.feature.mailbox.resources.card_writes
import com.verdenroz.corvid.feature.mailbox.resources.card_writing_since
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun Facts(
    state: SenderCardState,
    latest: EmailSummary?,
    isSource: Boolean,
    wide: Boolean,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val window = state.window
    val counts = state.counts
    val signer = (latest?.provenance?.seal as? Seal.Signed)?.signer
    val authenticity =
        Fact.Text(
            Res.string.card_authenticity,
            window?.let { authenticityFact(it, counts?.received, if (isSource) signer else null)?.asString() },
            warn = window != null && window.signed < window.size,
        )
    val trackers = Fact.Text(Res.string.card_trackers, window?.let { trackersFact(it, counts?.received)?.asString() })
    val since = counts?.firstSeen?.let { monthYear(it, zone).asString() }
    val facts =
        if (isSource) {
            val writes =
                if (counts != null && since != null) {
                    pluralStringResource(Res.plurals.card_messages_since, counts.received, counts.received, since)
                } else {
                    null
                }
            listOf(authenticity, Fact.Text(Res.string.card_writes, writes), trackers, Fact.Labels(Res.string.card_filed_as, latest?.labels))
        } else {
            val between = counts?.let { stringResource(Res.string.card_between, it.received, it.sent) }
            listOf(authenticity, Fact.Text(Res.string.card_writing_since, since), Fact.Text(Res.string.card_between_you, between), trackers)
        }
    val gutter = CorvidSpacing.gutter(wide)
    Column {
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { index, fact ->
                    if (index > 0) VerticalDivider(color = CorvidTheme.colors.line)
                    FactCell(fact, Modifier.weight(1f).fillMaxHeight().padding(horizontal = gutter, vertical = 12.dp))
                }
            }
            HorizontalDivider(color = CorvidTheme.colors.line)
        }
    }
}

private sealed interface Fact {
    val key: StringResource

    data class Text(override val key: StringResource, val value: String?, val warn: Boolean = false) : Fact

    /** [labels] is null until the newest message is known. */
    data class Labels(override val key: StringResource, val labels: List<Label>?) : Fact
}

@Composable
private fun FactCell(
    fact: Fact,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(fact.key).uppercase(), style = CorvidTheme.type.label, color = colors.inkFaint)
        when (fact) {
            is Fact.Text ->
                Text(
                    fact.value.orEmpty(),
                    style = CorvidTheme.type.metaStrong,
                    color = if (fact.warn) colors.danger else colors.ink,
                )
            is Fact.Labels ->
                when {
                    fact.labels == null -> Unit
                    fact.labels.isEmpty() -> Text(stringResource(Res.string.card_not_labelled), style = CorvidTheme.type.metaStrong)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { fact.labels.forEach { LabelChip(it) } }
                }
        }
    }
}
