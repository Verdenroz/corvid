package com.verdenroz.corvid.core.html

data class Marks(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
)

/** [mismatched] is set when the link's visible text names a different host than [href]. */
data class Link(val href: String, val mismatched: Boolean = false)

sealed interface Inline {
    data class Text(val text: String, val marks: Marks = Marks(), val link: Link? = null) : Inline

    data object Break : Inline
}

sealed interface HtmlBlock {
    data class Paragraph(val inlines: List<Inline>) : HtmlBlock

    data class Heading(val level: Int, val inlines: List<Inline>) : HtmlBlock

    data class Quote(val blocks: List<HtmlBlock>) : HtmlBlock

    data class Preformatted(val text: String) : HtmlBlock

    data object Rule : HtmlBlock

    data class Items(val ordered: Boolean, val start: Int, val items: List<List<HtmlBlock>>) : HtmlBlock

    /** [url] is null for an image that must not be fetched; [alt] is what stands in for it. */
    data class Image(val url: String?, val alt: String, val link: Link?) : HtmlBlock
}
