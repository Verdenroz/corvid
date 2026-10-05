package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

internal fun AnnotatedString.Builder.liveLink(
    href: String,
    color: Color,
    onOpen: (String) -> Unit,
    content: AnnotatedString.Builder.() -> Unit,
) {
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Url(href, style) { onOpen(href) }, content)
}

internal fun deadStyle(color: Color) = SpanStyle(color = color, textDecoration = TextDecoration.LineThrough)

internal fun AnnotatedString.Builder.deadNote(
    color: Color,
    note: String,
) {
    append(" ")
    withStyle(SpanStyle(color = color)) { append(note) }
}
