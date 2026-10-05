package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.html.HtmlBlock
import com.verdenroz.corvid.core.html.Inline
import com.verdenroz.corvid.core.model.Provenance
import com.verdenroz.corvid.core.ui.provenance.openUntrusted
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.reader_image_blocked
import com.verdenroz.corvid.feature.mailbox.resources.reader_link_disabled
import com.verdenroz.corvid.feature.mailbox.resources.reader_link_mismatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/** What the blocks need besides themselves: whose links these are and where images come from. */
internal class BodyContext(
    val provenance: Provenance,
    val loadImage: suspend (String) -> ByteArray?,
)

@Composable
internal fun Blocks(
    blocks: List<HtmlBlock>,
    context: BodyContext,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { Block(it, context) }
    }
}

@Composable
private fun Block(
    block: HtmlBlock,
    context: BodyContext,
) {
    val colors = CorvidTheme.colors
    when (block) {
        is HtmlBlock.Paragraph -> Inlines(block.inlines, context, CorvidTheme.type.body.copy(color = colors.ink))
        is HtmlBlock.Heading -> Inlines(block.inlines, context, headingStyle(block.level))
        is HtmlBlock.Quote -> Quote(block, context)
        is HtmlBlock.Preformatted -> Preformatted(block.text)
        is HtmlBlock.Rule -> HorizontalDivider(color = colors.line)
        is HtmlBlock.Items -> Items(block, context)
        is HtmlBlock.Image -> BodyImage(block, context)
    }
}

@Composable
private fun headingStyle(level: Int) =
    when (level) {
        1 -> CorvidTheme.type.title
        2 -> CorvidTheme.type.dayHeader
        else -> CorvidTheme.type.body.copy(fontWeight = FontWeight.SemiBold)
    }.copy(color = CorvidTheme.colors.ink)

@Composable
private fun Inlines(
    inlines: List<Inline>,
    context: BodyContext,
    style: TextStyle,
) {
    val colors = CorvidTheme.colors
    val uriHandler = LocalUriHandler.current
    val disabledNote = stringResource(Res.string.reader_link_disabled)
    val mismatchNotes =
        inlines
            .mapNotNull { (it as? Inline.Text)?.link?.takeIf { link -> link.mismatched }?.host }
            .distinct()
            .associateWith { stringResource(Res.string.reader_link_mismatch, it) }
    val palette = BodyPalette(link = colors.accent, dead = colors.inkFaint, warning = colors.danger, code = colors.chip)
    Text(
        inlineText(
            inlines,
            disabled = context.provenance.suspect,
            palette = palette,
            disabledNote = disabledNote,
            mismatchNote = { mismatchNotes.getValue(it) },
            onOpen = { uriHandler.openUntrusted(it, context.provenance) },
        ),
        style = style,
    )
}

@Composable
private fun Quote(
    quote: HtmlBlock.Quote,
    context: BodyContext,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(CorvidTheme.colors.line))
        Blocks(quote.blocks, context, Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun Preformatted(text: String) {
    val colors = CorvidTheme.colors
    Text(
        text,
        style = CorvidTheme.type.meta.copy(fontFamily = FontFamily.Monospace, color = colors.ink),
        softWrap = false,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.chip, CorvidShape.small)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
    )
}

@Composable
private fun Items(
    list: HtmlBlock.Items,
    context: BodyContext,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.items.forEachIndexed { index, item ->
            Row {
                Text(
                    if (list.ordered) "${list.start + index}." else "•",
                    style = CorvidTheme.type.body.copy(color = CorvidTheme.colors.inkMuted),
                    modifier = Modifier.width(26.dp),
                )
                Blocks(item, context, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BodyImage(
    image: HtmlBlock.Image,
    context: BodyContext,
) {
    val uriHandler = LocalUriHandler.current
    val link = image.link
    val open =
        if (link == null || context.provenance.suspect) {
            Modifier
        } else {
            Modifier.clickable {
                uriHandler.openUntrusted(
                    link.href,
                    context.provenance,
                )
            }
        }
    val bitmap by produceState<ImageBitmap?>(null, image.url) {
        value = image.url?.let { url -> context.loadImage(url)?.let { decode(it) } }
    }
    val loaded = bitmap
    if (loaded == null) {
        ImagePlaceholder(image.alt, open)
    } else {
        val width = with(LocalDensity.current) { loaded.width.toDp() }
        Image(
            loaded,
            contentDescription = image.alt.ifBlank { null },
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopStart,
            modifier = open.fillMaxWidth().widthIn(max = width),
        )
    }
}

private suspend fun decode(bytes: ByteArray): ImageBitmap? =
    withContext(Dispatchers.Default) {
        try {
            bytes.decodeToImageBitmap()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

@Composable
private fun ImagePlaceholder(
    alt: String,
    modifier: Modifier,
) {
    val colors = CorvidTheme.colors
    Text(
        alt.ifBlank { stringResource(Res.string.reader_image_blocked) },
        style = CorvidTheme.type.meta.copy(color = colors.inkFaint),
        modifier = modifier.border(1.dp, colors.line, CorvidShape.small).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
