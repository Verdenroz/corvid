package com.verdenroz.corvid.feature.mailbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.html.HtmlBlock
import com.verdenroz.corvid.core.html.htmlToBlocks
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.ui.provenance.openUntrusted
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.mailbox_truncated
import com.verdenroz.corvid.feature.mailbox.resources.reader_link_disabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private sealed interface Parsed {
    data object Pending : Parsed

    data object Plain : Parsed

    data class Ready(val blocks: List<HtmlBlock>) : Parsed
}

/**
 * A message's body: its HTML part drawn natively and sanitised, or the plain text when there is none
 * or it will not parse. Links open through [openUntrusted] and images load only from [repository]'s proxy path.
 */
@Composable
fun MessageBody(
    email: EmailDetail,
    repository: MailboxRepository,
    modifier: Modifier = Modifier,
) {
    val html = email.html
    val parsed = html?.let { parse(it.html, repository).value } ?: Parsed.Plain
    SelectionContainer(modifier) {
        Column {
            when (parsed) {
                Parsed.Pending -> Unit
                Parsed.Plain -> PlainBody(email)
                is Parsed.Ready -> Blocks(parsed.blocks, BodyContext(email.provenance, repository::proxiedImage))
            }
            if (html?.truncated ?: email.bodyTruncated) {
                Text(
                    stringResource(Res.string.mailbox_truncated),
                    style = CorvidTheme.type.meta,
                    color = CorvidTheme.colors.danger,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun parse(
    html: String,
    repository: MailboxRepository,
): State<Parsed> =
    produceState<Parsed>(Parsed.Pending, html, repository) {
        value =
            withContext(Dispatchers.Default) {
                try {
                    Parsed.Ready(htmlToBlocks(html, repository.imageProxyBase))
                } catch (e: RuntimeException) {
                    Parsed.Plain
                }
            }
    }

@Composable
private fun PlainBody(email: EmailDetail) {
    val colors = CorvidTheme.colors
    val uriHandler = LocalUriHandler.current
    Text(
        messageText(
            email.body,
            disabled = email.provenance.suspect,
            linkColor = colors.accent,
            deadColor = colors.inkFaint,
            disabledNote = stringResource(Res.string.reader_link_disabled),
            onOpen = { uriHandler.openUntrusted(it, email.provenance) },
        ),
        style = CorvidTheme.type.body,
        color = colors.ink,
    )
}
