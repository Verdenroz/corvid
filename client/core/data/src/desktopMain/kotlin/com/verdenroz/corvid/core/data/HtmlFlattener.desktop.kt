package com.verdenroz.corvid.core.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

private val BLOCK_TAGS = setOf("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre")
private val HIDDEN_TAGS = setOf("script", "style")

actual fun flattenHtml(html: String): String {
    val builder = StringBuilder()
    NodeTraversor.traverse(FlatteningVisitor(builder), Jsoup.parse(html).body())
    return builder
        .toString()
        .replace(Regex("\n{3,}"), "\n\n")
        .lineSequence()
        .joinToString("\n") { it.trim() }
        .trim()
}

/** Walks the DOM directly rather than round-tripping through HTML text, so entities
 *  stay decoded and a literal backslash-n in the mail's own text is never mistaken
 *  for a marker this flattener inserted. */
private class FlatteningVisitor(private val builder: StringBuilder) : NodeVisitor {
    override fun head(
        node: Node,
        depth: Int,
    ) {
        when {
            node is TextNode && !node.hasHiddenAncestor() -> {
                builder.append(if (node.hasPreformattedAncestor()) node.wholeText else node.text())
            }
            node is Element && node.tagName() == "br" -> builder.append('\n')
            node is Element && node.tagName() in BLOCK_TAGS -> builder.append("\n\n")
        }
    }

    override fun tail(
        node: Node,
        depth: Int,
    ) {
        if (node is Element && node.tagName() in BLOCK_TAGS) builder.append("\n\n")
    }
}

private fun Node.hasHiddenAncestor(): Boolean = ancestors().any { it.tagName() in HIDDEN_TAGS }

private fun Node.hasPreformattedAncestor(): Boolean = ancestors().any { it.tagName() == "pre" }

private fun Node.ancestors(): List<Element> {
    val result = mutableListOf<Element>()
    var current: Node? = parent()
    while (current != null) {
        if (current is Element) result.add(current)
        current = current.parent()
    }
    return result
}
