package com.verdenroz.corvid.feature.mailbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.verdenroz.corvid.core.html.Inline
import com.verdenroz.corvid.core.html.Link
import com.verdenroz.corvid.core.html.Marks
import kotlin.test.Test
import kotlin.test.assertEquals

private val PALETTE = BodyPalette(link = Color.Blue, dead = Color.Gray, warning = Color.Red, code = Color.Yellow)

private fun render(
    vararg inlines: Inline,
    disabled: Boolean = false,
    opened: MutableList<String> = mutableListOf(),
) = inlineText(inlines.toList(), disabled, PALETTE, "(disabled)", { "(goes to $it)" }) { opened += it }

private fun AnnotatedString.links() = getLinkAnnotations(0, length).map { (it.item as LinkAnnotation.Url).url }

class HtmlTextTest {
    @Test
    fun `a link in ordinary mail is live and opens its href`() {
        val opened = mutableListOf<String>()
        val text = render(Inline.Text("Hi "), Inline.Text("here", link = Link("https://a.test/x")), opened = opened)

        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        link.linkInteractionListener?.onClick(link)

        assertEquals("Hi here", text.text)
        assertEquals(listOf("https://a.test/x"), opened)
    }

    @Test
    fun `a link in suspicious mail is struck through, dead and says why`() {
        val text = render(Inline.Text("here", link = Link("https://a.test/x")), disabled = true)

        assertEquals(emptyList(), text.links())
        assertEquals("here (disabled)", text.text)
        assertEquals(TextDecoration.LineThrough, text.spanStyles.first().item.textDecoration)
        assertEquals(Color.Gray, text.spanStyles.first().item.color)
    }

    @Test
    fun `a link split by emphasis is one link with one disabled note`() {
        val link = Link("https://a.test/x")
        val parts = arrayOf(Inline.Text("a ", link = link), Inline.Text("b", Marks(bold = true), link))

        assertEquals(listOf("https://a.test/x"), render(*parts).links())
        assertEquals("a b (disabled)", render(*parts, disabled = true).text)
    }

    @Test
    fun `a mismatched link names the host it goes to`() {
        val text = render(Inline.Text("paypal.com", link = Link("https://evil.test/login", mismatched = true)))

        assertEquals("paypal.com (goes to evil.test)", text.text)
        assertEquals(listOf("https://evil.test/login"), text.links())
    }

    @Test
    fun `a mismatched link in suspicious mail shows only the disabled note`() {
        val text = render(Inline.Text("paypal.com", link = Link("https://evil.test/login", mismatched = true)), disabled = true)

        assertEquals("paypal.com (disabled)", text.text)
    }

    @Test
    fun `breaks become newlines and marks become styles`() {
        val text = render(Inline.Text("a", Marks(bold = true)), Inline.Break, Inline.Text("b"))

        assertEquals("a\nb", text.text)
        assertEquals(FontWeight.Bold, text.spanStyles.first().item.fontWeight)
    }
}
