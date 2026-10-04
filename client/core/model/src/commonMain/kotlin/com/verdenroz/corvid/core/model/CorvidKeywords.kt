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

    /** True for a keyword the user set, the only kind that trains the per-sender prior. */
    fun isUserKeyword(keyword: String): Boolean = !keyword.startsWith(NAMESPACE) && !keyword.startsWith('$')

    /** JMAP keywords are ASCII with no spaces or `(){]%*"\`, so anything else in a label becomes `-`. */
    fun ruleKeyword(label: String): String =
        RULE + label.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in "-_.") it else '-' }.joinToString("")

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

/** A label and who set it: the user, the labeler's per-sender prior, the model, or a filter. */
sealed interface Label {
    val name: String

    data class Yours(override val name: String) : Label

    data class Labeler(override val name: String) : Label

    data class Guess(override val name: String) : Label

    data class Rule(override val name: String) : Label
}
