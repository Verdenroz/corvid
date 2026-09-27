package com.verdenroz.corvid.core.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

private const val BLOCK_TAGS = "p, div, li, tr, h1, h2, h3, h4, h5, h6"

actual fun flattenHtml(html: String): String {
    val document = Jsoup.parse(html)
    document.outputSettings().prettyPrint(false)
    // A literal "\n" survives serialization as plain text; a real newline in a text
    // node does not, since the parser treats it as ordinary whitespace and collapses it.
    document.select("br").append("\\n")
    document.select(BLOCK_TAGS).prepend("\\n\\n")
    val withBreaks = document.html().replace("\\n", "\n")
    val settings = Document.OutputSettings().prettyPrint(false)
    return Jsoup.clean(withBreaks, "", Safelist.none(), settings)
        .lineSequence()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
