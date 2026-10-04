package com.verdenroz.corvid.core.model

/**
 * The keyword namespaces Corvid writes. The server's ingest excludes everything
 * under [NAMESPACE] and every `$` keyword from training (`own_labels` in
 * `crates/corvid-ingest/src/lib.rs`), so this must stay in step with it.
 */
object CorvidKeywords {
    const val NAMESPACE = "corvid-"
    const val RULE = "corvid-rule-"
    const val MODEL_LABEL = "corvid-llm-label-"
    const val MODEL = "corvid-llm-"
    const val SEEN = "\$seen"
    const val FLAGGED = "\$flagged"
    const val DRAFT = "\$draft"

    /** True for a keyword the user set, the only kind that trains the per-sender prior. */
    fun isUserKeyword(keyword: String): Boolean = !keyword.startsWith(NAMESPACE) && !keyword.startsWith('$')

    /** JMAP keywords are ASCII with no spaces or `(){]%*"\`, so anything else in a label becomes `-`. */
    fun ruleKeyword(label: String): String = RULE + sanitised(label)

    /** The user's own keyword for [label]; null when it would read as a flag or as Corvid's. */
    fun userKeyword(label: String): String? =
        sanitised(label.trim()).takeIf { it.isNotEmpty() && isUserKeyword(it) && it.any(Char::isLetterOrDigit) }

    private fun sanitised(label: String): String =
        label.lowercase().map {
            if (it in 'a'..'z' || it in '0'..'9' || it in "-_.") it else '-'
        }.joinToString("")

    /**
     * Who put a keyword on a message, for showing it as a label. Null for `$` flags and the
     * model's own markers (`corvid-llm-done`), which are bookkeeping rather than labels.
     */
    fun labelOf(keyword: String): Label? =
        when {
            keyword.startsWith('$') -> null
            keyword.startsWith(MODEL_LABEL) -> Label.Guess(keyword.removePrefix(MODEL_LABEL))
            keyword.startsWith(MODEL) -> null
            keyword.startsWith(RULE) -> Label.Rule(keyword.removePrefix(RULE))
            keyword.startsWith(NAMESPACE) -> Label.Labeler(keyword.removePrefix(NAMESPACE))
            else -> Label.Yours(keyword)
        }
}

/** The labels among [keywords] in name order; flags and Corvid's bookkeeping are not labels. */
fun labelsOf(keywords: Set<String>): List<Label> = keywords.mapNotNull(CorvidKeywords::labelOf).sortedBy { it.name }

val EmailSummary.labels: List<Label> get() = labelsOf(keywords)

val EmailDetail.labels: List<Label> get() = labelsOf(keywords)

/** A label and who set it: the user, the labeler's per-sender prior, the model, or a filter. */
sealed interface Label {
    val name: String

    data class Yours(override val name: String) : Label

    data class Labeler(override val name: String) : Label

    data class Guess(override val name: String) : Label

    data class Rule(override val name: String) : Label
}
