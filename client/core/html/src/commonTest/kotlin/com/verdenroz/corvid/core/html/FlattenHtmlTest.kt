package com.verdenroz.corvid.core.html

import kotlin.test.Test
import kotlin.test.assertEquals

class FlattenHtmlTest {
    @Test
    fun `blocks become paragraphs and breaks become lines`() {
        assertEquals("one\ntwo\n\nthree", flattenHtml("<p>one<br>two</p><p>three</p>"))
    }

    @Test
    fun `entities stay decoded`() {
        assertEquals("a & b <c>", flattenHtml("<p>a &amp; b &lt;c&gt;</p>"))
    }

    @Test
    fun `a pre keeps its indentation`() {
        assertEquals("fn x() {\n    y\n}", flattenHtml("<pre>fn x() {\n    y\n}</pre>"))
    }

    @Test
    fun `hidden preheader text is absent`() {
        val html =
            """<div style="display: none">preheader</div><span hidden>tracking</span>""" +
                """<p style="visibility:hidden">ghost</p><script>var x</script><form><button>go</button></form><p>shown</p>"""
        assertEquals("shown", flattenHtml(html))
    }

    @Test
    fun `text after a hidden element is kept`() {
        assertEquals("before\n\nafter", flattenHtml("<p>before</p><div hidden><p>gone</p></div><p>after</p>"))
    }
}
