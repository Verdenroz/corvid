package com.verdenroz.corvid.core.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PROXY = "https://mail.test:4443/i/"

private fun blocks(html: String) = htmlToBlocks(html, PROXY)

private fun text(
    value: String,
    marks: Marks = Marks(),
    link: Link? = null,
) = Inline.Text(value, marks, link)

private fun paragraph(vararg inlines: Inline) = HtmlBlock.Paragraph(inlines.toList())

private fun linkOf(html: String) = ((blocks(html).single() as HtmlBlock.Paragraph).inlines.single() as Inline.Text).link

private fun plain(blocks: List<HtmlBlock>): String =
    blocks.joinToString("\n") { block ->
        when (block) {
            is HtmlBlock.Paragraph -> block.inlines.joinToString("") { (it as? Inline.Text)?.text ?: "\n" }
            is HtmlBlock.Heading -> block.inlines.joinToString("") { (it as? Inline.Text)?.text.orEmpty() }
            else -> block.toString()
        }
    }

class HtmlToBlocksTest {
    @Test
    fun `paragraphs and line breaks become paragraphs and breaks`() {
        assertEquals(
            listOf(paragraph(text("one"), Inline.Break, text("two")), paragraph(text("three"))),
            blocks("<p>one<br>two</p><p>three</p>"),
        )
    }

    @Test
    fun `bare text and divs each flow into their own paragraph`() {
        assertEquals(listOf(paragraph(text("a")), paragraph(text("b")), paragraph(text("c"))), blocks("a<div>b</div>c"))
    }

    @Test
    fun `whitespace collapses and entities decode`() {
        assertEquals(listOf(paragraph(text("Tom & Jerry <3 ©"))), blocks("<p>  Tom\n &amp;   Jerry &lt;3 &copy;  </p>"))
    }

    @Test
    fun `a paragraph holding only a non-breaking space is dropped`() {
        assertEquals(listOf(paragraph(text("x"))), blocks("<p>&nbsp;</p><p>x</p>"))
    }

    @Test
    fun `emphasis marks nest`() {
        assertEquals(
            listOf(
                paragraph(
                    text("a "),
                    text("b", Marks(bold = true)),
                    text("c", Marks(bold = true, italic = true)),
                    text(" "),
                    text("d", Marks(strike = true)),
                    text(" "),
                    text("e", Marks(underline = true)),
                    text(" "),
                    text("f", Marks(code = true)),
                ),
            ),
            blocks("a <b>b<i>c</i></b> <del>d</del> <u>e</u> <code>f</code>"),
        )
    }

    @Test
    fun `headings keep their level`() {
        assertEquals(listOf(HtmlBlock.Heading(2, listOf(text("Title"))), paragraph(text("body"))), blocks("<h2>Title</h2>body"))
    }

    @Test
    fun `lists keep order and nesting`() {
        val list = blocks("<ol start=3><li>a<ul><li>x</li><li>y</li></ul></li><li>b</li></ol>").single() as HtmlBlock.Items

        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(paragraph(text("a")), list.items[0][0])
        assertEquals(HtmlBlock.Items(false, 1, listOf(listOf(paragraph(text("x"))), listOf(paragraph(text("y"))))), list.items[0][1])
        assertEquals(listOf(paragraph(text("b"))), list.items[1])
    }

    @Test
    fun `blockquotes nest their blocks`() {
        assertEquals(
            listOf(HtmlBlock.Quote(listOf(paragraph(text("a")), HtmlBlock.Quote(listOf(paragraph(text("b"))))))),
            blocks("<blockquote><p>a</p><blockquote>b</blockquote></blockquote>"),
        )
    }

    @Test
    fun `preformatted text keeps its indentation and line breaks`() {
        assertEquals(
            listOf(HtmlBlock.Preformatted("fun f() {\n    x\n}")),
            blocks("<pre>\nfun f() {<br>    <b>x</b>\n}</pre>"),
        )
    }

    @Test
    fun `a rule separates blocks`() {
        assertEquals(listOf(paragraph(text("a")), HtmlBlock.Rule, paragraph(text("b"))), blocks("a<hr>b"))
    }

    @Test
    fun `table cells flatten to one readable row in reading order`() {
        assertEquals(
            listOf(paragraph(text("Item"), text(" · "), text("Qty")), paragraph(text("Tea"), text(" · "), text("2"))),
            blocks("<table><tr><th>Item</th><th>Qty</th></tr><tr><td>Tea</td><td>2</td></tr></table>"),
        )
    }

    @Test
    fun `a layout table keeps each cell's blocks in order`() {
        val html =
            "<table><tr><td><p>left</p><p>more</p></td><td><img src='${PROXY}a' alt='pic'></td></tr>" +
                "<tr><td><table><tr><td>deep</td></tr></table></td></tr></table>"

        assertEquals(
            listOf(paragraph(text("left")), paragraph(text("more")), HtmlBlock.Image("${PROXY}a", "pic", null), paragraph(text("deep"))),
            blocks(html),
        )
    }

    @Test
    fun `scripts styles forms frames and embeds vanish with their content`() {
        val html =
            "<p>keep</p><script>alert(1)</script><style>p{color:red}</style><form><input value=x>hi</form>" +
                "<iframe src='https://evil.test'>f</iframe><object data=x>o</object><embed src=x><svg><text>s</text></svg>" +
                "<noscript><img src='https://t.test/p.gif'></noscript>"

        assertEquals(listOf(paragraph(text("keep"))), blocks(html))
    }

    @Test
    fun `event handlers and styles never reach the model`() {
        assertEquals(
            listOf(paragraph(text("hi"))),
            blocks("<p onclick='steal()' style='color:red;font-family:x' class=a>hi</p>"),
        )
    }

    @Test
    fun `hidden elements are dropped`() {
        val html = "<p style='display: none'>preheader</p><p hidden>h</p><div style='VISIBILITY:hidden'>v</div><p>shown</p>"

        assertEquals(listOf(paragraph(text("shown"))), blocks(html))
    }

    @Test
    fun `an image on the proxy path loads`() {
        assertEquals(
            listOf(HtmlBlock.Image("${PROXY}abc.sig", "logo", null)),
            blocks("<img src='${PROXY}abc.sig' alt=' logo '>"),
        )
    }

    @Test
    fun `an image anywhere else never gets a url and keeps its alt`() {
        val sources =
            listOf(
                "https://cdn.test/i/a.png",
                "https://mail.test:4443/other/a.png",
                "https://mail.test/i/a.png",
                "https://mail.test:4443@evil.test/i/a.png",
                "http://mail.test:4443/i/a.png",
                "data:image/png;base64,AAAA",
                "cid:logo@x",
                "//mail.test:4443/i/a.png",
                "/i/a.png",
                "",
            )

        for (src in sources) {
            assertEquals(listOf(HtmlBlock.Image(null, "alt", null)), blocks("<img src='$src' alt='alt'>"), src)
        }
    }

    @Test
    fun `tracking pixels are skipped`() {
        assertEquals(emptyList(), blocks("<img src='${PROXY}p' width=1 height=1><img src='${PROXY}q' height='0'>"))
    }

    @Test
    fun `an image inside a link carries the link`() {
        assertEquals(
            listOf(HtmlBlock.Image("${PROXY}a", "", Link("https://shop.test/x"))),
            blocks("<a href='https://shop.test/x'><img src='${PROXY}a'></a>"),
        )
    }

    @Test
    fun `web and mail links stay links and other schemes become text`() {
        assertEquals(
            listOf(
                paragraph(
                    text("web", link = Link("https://a.test/x")),
                    text(" "),
                    text("mail", link = Link("mailto:me@a.test")),
                    text(" js data tel rel"),
                ),
            ),
            blocks(
                "<a href='https://a.test/x'>web</a> <a href='mailto:me@a.test'>mail</a> <a href='javascript:alert(1)'>js</a> " +
                    "<a href='data:text/html,x'>data</a> <a href='tel:1'>tel</a> <a href='/rel'>rel</a>",
            ),
        )
    }

    @Test
    fun `a link whose text is another address is marked mismatched`() {
        assertEquals(true, linkOf("<a href='https://evil.test/login'>https://www.paypal.com/signin</a>")?.mismatched)
        assertEquals(true, linkOf("<a href='https://evil.test/login'>paypal.com</a>")?.mismatched)
    }

    @Test
    fun `a link whose text matches its host or reads as words is not marked`() {
        for (anchor in listOf(
            "<a href='https://www.paypal.com/x'>paypal.com</a>",
            "<a href='https://PayPal.com:8443/x'>https://www.paypal.com/y</a>",
            "<a href='https://evil.test/x'>Sign in to your account</a>",
            "<a href='https://evil.test/x'>v1.2</a>",
        )) {
            assertEquals(false, linkOf(anchor)?.mismatched, anchor)
        }
    }

    @Test
    fun `a link with userinfo is judged by the host it really reaches`() {
        assertEquals(true, linkOf("<a href='https://paypal.com@evil.test/'>paypal.com</a>")?.mismatched)
    }

    @Test
    fun `unclosed and misnested markup still yields its text`() {
        assertEquals("a b c", plain(blocks("<p>a <b>b <i>c</p></b></i>")))
        assertEquals("one\ntwo", plain(blocks("<div>one<p>two")))
    }

    @Test
    fun `a body cut mid-tag renders what it has`() {
        assertEquals("kept", plain(blocks("<p>kept</p><p><a href='https://x.test/lo")))
    }

    @Test
    fun `input that is not markup is one paragraph and empty input is nothing`() {
        assertEquals(listOf(paragraph(text("just words"))), blocks("just words"))
        assertEquals(emptyList(), blocks(""))
    }

    @Test
    fun `extreme nesting does not overflow the stack`() {
        val html = "<div>".repeat(5_000) + "deep" + "</div>".repeat(5_000)

        assertTrue("deep" in plain(blocks(html)))
    }
}
