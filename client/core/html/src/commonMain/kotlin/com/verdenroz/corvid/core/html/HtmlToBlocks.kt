package com.verdenroz.corvid.core.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

private const val MAX_DEPTH = 64
private const val CELL_SEPARATOR = " · "
private val COLLAPSIBLE = Regex("""[ \t\r\n\u000c]+""")
private val HEADINGS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)
private val BLOCK_TAGS =
    setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "nav", "address", "figure", "figcaption",
        "center", "details", "summary", "dl", "dt", "dd", "fieldset", "legend", "table", "thead", "tbody", "tfoot",
        "caption", "td", "th", "li",
    )

/**
 * The mail's HTML as the blocks the reader draws. [imageProxyBase] is the user's own image
 * proxy path (ending in `/i/`); an image elsewhere comes out with no URL.
 */
fun htmlToBlocks(
    html: String,
    imageProxyBase: String,
): List<HtmlBlock> = Flow(imageProxyBase).apply { add(Ksoup.parse(html).body().childNodes(), Ctx(), 0) }.finish()

private data class Ctx(val marks: Marks = Marks(), val link: Link? = null)

private class Flow(private val proxyBase: String) {
    private val blocks = mutableListOf<HtmlBlock>()
    private val run = mutableListOf<Inline>()

    fun finish(): List<HtmlBlock> {
        flush()
        return blocks
    }

    fun add(
        nodes: List<Node>,
        ctx: Ctx,
        depth: Int,
    ) {
        for (node in nodes) {
            when {
                node is TextNode -> text(node.getWholeText(), ctx)
                node is Element && !node.isDropped() -> element(node, ctx, depth)
            }
        }
    }

    private fun element(
        el: Element,
        ctx: Ctx,
        depth: Int,
    ) {
        val tag = el.tagName()
        when {
            depth >= MAX_DEPTH -> text(el.text(), ctx)
            tag == "br" -> lineBreak()
            tag == "hr" -> emit(HtmlBlock.Rule)
            tag == "img" -> image(el, ctx)
            tag == "a" -> link(el, ctx, depth)
            tag == "pre" -> emit(HtmlBlock.Preformatted(preformatted(el)).takeIf { it.text.isNotBlank() })
            tag == "blockquote" -> nested(el, ctx, depth).takeIf { it.isNotEmpty() }?.let { emit(HtmlBlock.Quote(it)) }
            tag in HEADINGS -> heading(HEADINGS.getValue(tag), nested(el, ctx, depth))
            tag == "ul" || tag == "ol" -> items(el, ctx, depth)
            tag == "tr" -> row(el, ctx, depth)
            tag in BLOCK_TAGS -> {
                flush()
                add(el.childNodes(), ctx, depth + 1)
                flush()
            }
            else -> add(el.childNodes(), marked(tag, ctx), depth + 1)
        }
    }

    private fun marked(
        tag: String,
        ctx: Ctx,
    ): Ctx =
        when (tag) {
            "b", "strong" -> ctx.copy(marks = ctx.marks.copy(bold = true))
            "i", "em", "cite", "dfn", "var" -> ctx.copy(marks = ctx.marks.copy(italic = true))
            "u", "ins" -> ctx.copy(marks = ctx.marks.copy(underline = true))
            "s", "strike", "del" -> ctx.copy(marks = ctx.marks.copy(strike = true))
            "code", "tt", "kbd", "samp" -> ctx.copy(marks = ctx.marks.copy(code = true))
            else -> ctx
        }

    private fun link(
        el: Element,
        ctx: Ctx,
        depth: Int,
    ) {
        val href = safeLink(el.attr("href"))
        val target = href?.let { Link(it, mismatchedHost(el.text(), it)) }
        add(el.childNodes(), if (target == null) ctx else ctx.copy(link = target), depth + 1)
    }

    private fun image(
        el: Element,
        ctx: Ctx,
    ) {
        val tiny = listOf("width", "height").any { el.attr(it).trim().toIntOrNull()?.let { size -> size <= 1 } == true }
        if (tiny) return
        emit(HtmlBlock.Image(imageUrl(el.attr("src"), proxyBase), el.attr("alt").trim(), ctx.link))
    }

    private fun heading(
        level: Int,
        inner: List<HtmlBlock>,
    ) {
        flush()
        blocks += inner.map { if (it is HtmlBlock.Paragraph) HtmlBlock.Heading(level, it.inlines) else it }
    }

    private fun items(
        el: Element,
        ctx: Ctx,
        depth: Int,
    ) {
        val entries = el.children().filter { it.tagName() == "li" && !it.isDropped() }.map { nested(it, ctx, depth) }
        val start = el.attr("start").trim().toIntOrNull() ?: 1
        emit(HtmlBlock.Items(el.tagName() == "ol", start, entries.filter { it.isNotEmpty() }).takeIf { it.items.isNotEmpty() })
    }

    /** A row of plain cells reads as one line; a row holding anything richer keeps each cell's blocks in order. */
    private fun row(
        el: Element,
        ctx: Ctx,
        depth: Int,
    ) {
        val cells = el.children().filter { (it.tagName() == "td" || it.tagName() == "th") && !it.isDropped() }
        val filled = cells.map { nested(it, ctx, depth) }.filter { it.isNotEmpty() }
        flush()
        if (filled.size > 1 && filled.all { it.size == 1 && it.single() is HtmlBlock.Paragraph }) {
            val cellLines = filled.map { (it.single() as HtmlBlock.Paragraph).inlines }
            blocks += HtmlBlock.Paragraph(cellLines.reduce { line, cell -> line + Inline.Text(CELL_SEPARATOR) + cell })
        } else {
            blocks += filled.flatten()
        }
    }

    private fun nested(
        el: Element,
        ctx: Ctx,
        depth: Int,
    ): List<HtmlBlock> = Flow(proxyBase).apply { add(el.childNodes(), ctx, depth + 1) }.finish()

    private fun preformatted(el: Element): String =
        buildString {
            fun walk(nodes: List<Node>) {
                for (node in nodes) {
                    when {
                        node is TextNode -> append(node.getWholeText())
                        node is Element && node.isDropped() -> Unit
                        node is Element && node.tagName() == "br" -> append('\n')
                        node is Element -> walk(node.childNodes())
                    }
                }
            }
            walk(el.childNodes())
        }.trim('\n')

    private fun emit(block: HtmlBlock?) {
        flush()
        if (block != null) blocks += block
    }

    private fun text(
        raw: String,
        ctx: Ctx,
    ) {
        val collapsed = COLLAPSIBLE.replace(raw, " ")
        val afterSpace = run.lastOrNull().let { it == null || it is Inline.Break || (it as Inline.Text).text.endsWith(' ') }
        val text = if (afterSpace) collapsed.trimStart(' ') else collapsed
        if (text.isEmpty()) return
        val last = run.lastOrNull() as? Inline.Text
        if (last != null && last.marks == ctx.marks && last.link == ctx.link) {
            run[run.lastIndex] = last.copy(text = last.text + text)
        } else {
            run += Inline.Text(text, ctx.marks, ctx.link)
        }
    }

    private fun lineBreak() {
        if (run.isNotEmpty()) run += Inline.Break
    }

    private fun flush() {
        val inlines = run.toList().dropLastWhile { it is Inline.Break }.trimLastSpace()
        run.clear()
        if (inlines.any { it is Inline.Text && it.text.isNotBlank() }) blocks += HtmlBlock.Paragraph(inlines)
    }

    private fun List<Inline>.trimLastSpace(): List<Inline> {
        val last = lastOrNull() as? Inline.Text ?: return this
        val trimmed = last.text.trimEnd(' ')
        return if (trimmed.isEmpty()) dropLast(1).trimLastSpace() else dropLast(1) + last.copy(text = trimmed)
    }
}
