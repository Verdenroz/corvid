package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.verdenroz.corvid.core.html.Inline
import com.verdenroz.corvid.core.html.Link
import com.verdenroz.corvid.core.html.Marks

internal class BodyPalette(
    val link: Color,
    val dead: Color,
    val warning: Color,
    val code: Color,
)

/**
 * A run of inline content, its links live in [BodyPalette.link] or, when [disabled], struck through
 * and followed by [disabledNote]. A live link whose text names another host is followed by
 * [mismatchNote] for the host it really goes to.
 */
internal fun inlineText(
    inlines: List<Inline>,
    disabled: Boolean,
    palette: BodyPalette,
    disabledNote: String,
    mismatchNote: (String) -> String,
    onOpen: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < inlines.size) {
            val inline = inlines[index]
            val link = (inline as? Inline.Text)?.link
            when {
                inline is Inline.Break -> append('\n')
                link == null -> withStyle((inline as Inline.Text).marks.style(palette)) { append(inline.text) }
                else -> {
                    val run = inlines.drop(index).takeWhile { (it as? Inline.Text)?.link == link }.filterIsInstance<Inline.Text>()
                    if (disabled) deadLink(run, palette, disabledNote) else liveLink(run, link, palette, mismatchNote, onOpen)
                    index += run.size - 1
                }
            }
            index++
        }
    }

private fun AnnotatedString.Builder.liveLink(
    run: List<Inline.Text>,
    link: Link,
    palette: BodyPalette,
    mismatchNote: (String) -> String,
    onOpen: (String) -> Unit,
) {
    liveLink(link.href, palette.link, onOpen) {
        run.forEach { withStyle(it.marks.style(palette)) { append(it.text) } }
    }
    val host = link.host
    if (link.mismatched && host != null) {
        append(" ")
        withStyle(SpanStyle(color = palette.warning, fontSize = 0.85.em)) { append(mismatchNote(host)) }
    }
}

private fun AnnotatedString.Builder.deadLink(
    run: List<Inline.Text>,
    palette: BodyPalette,
    disabledNote: String,
) {
    run.forEach { withStyle(it.marks.style(palette).merge(deadStyle(palette.dead))) { append(it.text) } }
    deadNote(palette.dead, disabledNote)
}

private fun Marks.style(palette: BodyPalette): SpanStyle {
    val decorations = listOfNotNull(TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { strike })
    return SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        fontFamily = if (code) FontFamily.Monospace else null,
        background = if (code) palette.code else Color.Unspecified,
    )
}
