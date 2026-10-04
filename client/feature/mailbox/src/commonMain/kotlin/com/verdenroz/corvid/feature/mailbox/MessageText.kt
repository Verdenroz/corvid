package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

private val URL = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""")

/**
 * A plain-text body with its URLs either live in [linkColor] or, when [disabled], struck through
 * in [deadColor] and followed by [disabledNote], so a failed-authentication message cannot be clicked through.
 */
internal fun messageText(
    body: String,
    disabled: Boolean,
    linkColor: Color,
    deadColor: Color,
    disabledNote: String,
    onOpen: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        var from = 0
        for (match in URL.findAll(body)) {
            append(body.substring(from, match.range.first))
            if (disabled) {
                withStyle(SpanStyle(color = deadColor, textDecoration = TextDecoration.LineThrough)) { append(match.value) }
                append(" ")
                withStyle(SpanStyle(color = deadColor)) { append(disabledNote) }
            } else {
                withLink(
                    LinkAnnotation.Url(
                        match.value,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ) { onOpen(match.value) },
                ) {
                    append(match.value)
                }
            }
            from = match.range.last + 1
        }
        append(body.substring(from))
    }
