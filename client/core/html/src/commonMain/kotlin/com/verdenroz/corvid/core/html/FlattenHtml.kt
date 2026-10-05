package com.verdenroz.corvid.core.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.select.NodeTraversor
import com.fleeksoft.ksoup.select.NodeVisitor

private val FLAT_BLOCK_TAGS = setOf("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre")
private val BLANK_RUNS = Regex("\n{3,}")

/** The mail's HTML as plain text, minus what [htmlToBlocks] would not draw: hidden, scripted and form content. */
fun flattenHtml(html: String): String {
    val builder = StringBuilder()
    val preRanges = mutableListOf<IntRange>()
    NodeTraversor.traverse(FlatteningVisitor(builder, preRanges), Ksoup.parse(html).body())
    return trimOutsidePre(builder.toString(), preRanges).replace(BLANK_RUNS, "\n\n").trim()
}

/** Only a pre's own text is exempt from per-line trimming; trimming it too would
 *  strip the code indentation it exists to keep. */
private fun trimOutsidePre(
    text: String,
    preRanges: List<IntRange>,
): String {
    var offset = 0
    return text
        .split("\n")
        .joinToString("\n") { line ->
            val lineRange = offset until (offset + line.length)
            offset += line.length + 1
            if (preRanges.any { it.first <= lineRange.first && lineRange.last <= it.last }) line else line.trim()
        }
}

/** Walks the DOM directly rather than round-tripping through HTML text, so entities
 *  stay decoded and a literal backslash-n in the mail's own text is never mistaken
 *  for a marker this flattener inserted. */
private class FlatteningVisitor(
    private val builder: StringBuilder,
    private val preRanges: MutableList<IntRange>,
) : NodeVisitor {
    private var droppedDepth = 0
    private var preDepth = 0

    override fun head(
        node: Node,
        depth: Int,
    ) {
        if (node is Element) {
            if (node.isDropped()) droppedDepth++
            if (droppedDepth > 0) return
            if (node.tagName() == "pre") preDepth++
            if (node.tagName() == "br") builder.append('\n')
            if (node.tagName() in FLAT_BLOCK_TAGS) builder.append("\n\n")
        } else if (node is TextNode && droppedDepth == 0) {
            if (preDepth > 0) {
                val start = builder.length
                builder.append(node.getWholeText())
                preRanges += start until builder.length
            } else {
                builder.append(node.text())
            }
        }
    }

    override fun tail(
        node: Node,
        depth: Int,
    ) {
        if (node !is Element) return
        if (droppedDepth == 0) {
            if (node.tagName() == "pre") preDepth--
            if (node.tagName() in FLAT_BLOCK_TAGS) builder.append("\n\n")
        }
        if (node.isDropped()) droppedDepth--
    }
}
