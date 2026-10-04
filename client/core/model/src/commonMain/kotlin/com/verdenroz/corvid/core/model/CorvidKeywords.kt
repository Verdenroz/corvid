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

    /** True for a keyword the user set, the only kind that trains the per-sender prior. */
    fun isUserKeyword(keyword: String): Boolean = !keyword.startsWith(NAMESPACE) && !keyword.startsWith('$')

    /** JMAP keywords are ASCII with no spaces or `(){]%*"\`, so anything else in a label becomes `-`. */
    fun ruleKeyword(label: String): String =
        RULE + label.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in "-_.") it else '-' }.joinToString("")
}
