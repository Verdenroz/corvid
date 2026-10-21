package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.LabelCount
import com.verdenroz.corvid.core.designsystem.CorvidSpacing
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.LocalWideLayout
import com.verdenroz.corvid.core.model.Label
import com.verdenroz.corvid.core.ui.provenance.LabelChip
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.directory_messages
import com.verdenroz.corvid.feature.mailbox.resources.labels_empty
import com.verdenroz.corvid.feature.mailbox.resources.labels_guess
import com.verdenroz.corvid.feature.mailbox.resources.labels_labeler
import com.verdenroz.corvid.feature.mailbox.resources.labels_rule
import com.verdenroz.corvid.feature.mailbox.resources.labels_yours
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

/** Who set a label: the user, a filter the user wrote, the labeler's per-sender prior, then the model. */
private val SECTIONS: List<Pair<KClass<out Label>, StringResource>> =
    listOf(
        Label.Yours::class to Res.string.labels_yours,
        Label.Rule::class to Res.string.labels_rule,
        Label.Labeler::class to Res.string.labels_labeler,
        Label.Guess::class to Res.string.labels_guess,
    )

/** Every label in the window under who set it; a row searches for the messages carrying that exact keyword. */
@Composable
fun LabelsScreen(
    viewModel: DirectoryViewModel,
    onSearch: (String) -> Unit,
) {
    DirectoryList(viewModel, stringResource(Res.string.labels_empty), isEmpty = { it.labels.isEmpty() }) { directory ->
        SECTIONS.forEach { (kind, title) ->
            val labels = directory.labels.filter { kind.isInstance(it.label) }
            if (labels.isNotEmpty()) {
                item(key = title.key) { SectionTitle(stringResource(title)) }
                items(labels, key = { it.keyword }) { LabelRow(it) { onSearch("label:${it.keyword}") } }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        style = CorvidTheme.type.label,
        color = CorvidTheme.colors.inkFaint,
        modifier =
            Modifier.padding(
                start = CorvidSpacing.gutter(LocalWideLayout.current),
                top = 18.dp,
                bottom = 6.dp,
            ).semantics { heading() },
    )
}

@Composable
private fun LabelRow(
    count: LabelCount,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = CorvidSpacing.gutter(LocalWideLayout.current)),
        ) {
            LabelChip(count.label)
            Spacer(Modifier.weight(1f))
            Text(
                pluralStringResource(Res.plurals.directory_messages, count.messages, count.messages),
                style = CorvidTheme.type.metaNumeric,
                color = CorvidTheme.colors.inkFaint,
            )
        }
        HorizontalDivider(color = CorvidTheme.colors.line)
    }
}
