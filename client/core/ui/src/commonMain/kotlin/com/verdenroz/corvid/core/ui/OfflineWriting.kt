package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.decision_many
import com.verdenroz.corvid.core.ui.resources.decision_one
import com.verdenroz.corvid.core.ui.resources.outbox_label
import com.verdenroz.corvid.core.ui.resources.waiting_many
import com.verdenroz.corvid.core.ui.resources.waiting_one
import com.verdenroz.corvid.core.ui.resources.write_message
import org.jetbrains.compose.resources.stringResource

/** How many messages wait to be sent and how many need a decision, from counts alone: no recipient or subject is known while the key is gone. */
@Composable
internal fun outboxFacts(
    waiting: Int?,
    needDecision: Int,
): List<StateFact> {
    val label = stringResource(Res.string.outbox_label)
    return listOfNotNull(
        when {
            waiting == null || waiting == 0 -> null
            waiting == 1 -> StateFact(label, stringResource(Res.string.waiting_one))
            else -> StateFact(label, stringResource(Res.string.waiting_many, waiting))
        },
        when (needDecision) {
            0 -> null
            1 -> StateFact(label, stringResource(Res.string.decision_one))
            else -> StateFact(label, stringResource(Res.string.decision_many, needDecision))
        },
    )
}

@Composable
internal fun ColumnScope.WriteAction(onWrite: (() -> Unit)?) {
    onWrite?.let { QuietLink(stringResource(Res.string.write_message), it) }
}
