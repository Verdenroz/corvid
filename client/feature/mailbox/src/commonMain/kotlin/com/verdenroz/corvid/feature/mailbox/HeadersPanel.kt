package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.headers_loading
import org.jetbrains.compose.resources.stringResource

/** The message's header block exactly as the server holds it, selectable so it can be copied whole. */
@Composable
internal fun HeadersPanel(
    headers: HeadersState,
    modifier: Modifier = Modifier,
) {
    val colors = CorvidTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth().background(colors.rail, CorvidShape.medium).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        when (headers) {
            is HeadersState.Loading ->
                Text(
                    stringResource(Res.string.headers_loading),
                    style = CorvidTheme.type.meta,
                    color = colors.inkFaint,
                )
            is HeadersState.Failed -> Text(headers.message.asString(), style = CorvidTheme.type.meta, color = colors.danger)
            is HeadersState.Shown ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        headers.fields.forEach { field ->
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold)) { append(field.name) }
                                    append(": ")
                                    append(field.value)
                                },
                                style = CorvidTheme.type.meta,
                                color = colors.inkMuted,
                            )
                        }
                    }
                }
        }
    }
}
