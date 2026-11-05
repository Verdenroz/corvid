package com.verdenroz.corvid.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.outbox_label
import com.verdenroz.corvid.core.ui.resources.waiting_many
import com.verdenroz.corvid.core.ui.resources.waiting_one
import com.verdenroz.corvid.core.ui.resources.write_message
import org.jetbrains.compose.resources.stringResource

/** How many messages wait to be sent, from counts alone: no recipient or subject is known while the key is gone. */
@Composable
internal fun waitingFact(count: Int?): StateFact? =
    when {
        count == null || count == 0 -> null
        count == 1 -> StateFact(stringResource(Res.string.outbox_label), stringResource(Res.string.waiting_one))
        else -> StateFact(stringResource(Res.string.outbox_label), stringResource(Res.string.waiting_many, count))
    }

@Composable
internal fun ColumnScope.WriteAction(onWrite: (() -> Unit)?) {
    onWrite?.let { QuietLink(stringResource(Res.string.write_message), it) }
}
