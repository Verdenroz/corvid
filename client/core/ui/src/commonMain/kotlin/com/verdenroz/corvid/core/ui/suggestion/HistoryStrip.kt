package com.verdenroz.corvid.core.ui.suggestion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidTheme

private const val SLOTS = 30
private val SQUARE = RoundedCornerShape(2.dp)

/** One square per message, oldest first: filled where it matched, outlined where it did not. Slots keep one width however few there are. */
@Composable
fun HistoryStrip(
    history: List<Boolean>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
    ) {
        history.forEach { matched ->
            val square = Modifier.weight(1f).height(10.dp)
            Box(if (matched) square.background(colors.accent, SQUARE) else square.border(1.5.dp, colors.inkFaint, SQUARE))
        }
        repeat(SLOTS - history.size) { Box(Modifier.weight(1f)) }
    }
}
