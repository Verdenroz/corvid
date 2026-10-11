package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

private const val MAX_EXPANSIONS = 20
private const val MIN_FRAGMENT = 2
private const val MIN_WORD = 3
private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

/**
 * The addresses and words of the [MailSample], read once a session. The server's text
 * search covers subject, body, sender and recipients but matches whole words only, so "green"
 * never finds greenpieguy@gmail.com and "invo" never finds an invoice. The word being typed is
 * completed against these first, and each completion is searched as a whole word.
 */
internal class SearchVocabulary(private val sample: MailSample) {
    private val lock = Mutex()
    private var known: Known? = null

    /** The word still being typed, matching as itself or as any of its completions. */
    suspend fun completions(fragment: String): JsonObject {
        val word = condition("text", fragment)
        if (fragment.length < MIN_FRAGMENT) return word
        val known = known()
        val addresses = known.addresses.filter { it.email.contains(fragment, true) || it.name?.contains(fragment, true) == true }
        val emails = addresses.map { it.email.lowercase() }.distinct().take(MAX_EXPANSIONS)
        val words = known.completions(fragment.lowercase()).take(MAX_EXPANSIONS)
        if (emails.isEmpty() && words.isEmpty()) return word
        val recipients = emails.flatMap { listOf(condition("from", it), condition("to", it)) }
        return operator("OR", listOf(word) + words.map { condition("text", it) } + recipients)
    }

    private suspend fun known(): Known = lock.withLock { known ?: build(sample.emails()).also { known = it } }

    private fun build(emails: List<SampledEmail>): Known {
        val addresses = emails.flatMap { it.from + it.to + it.cc }.distinctBy { it.email.lowercase() }
        val text = emails.flatMap { listOfNotNull(it.subject, it.preview) }
        val words = text.flatMap { it.lowercase().split(SEPARATOR) }.filter { it.length >= MIN_WORD }
        return Known(addresses, words.groupingBy { it }.eachCount())
    }
}

/** Words are kept sorted, so the ones sharing a prefix sit in one run that a binary search finds. */
private class Known(val addresses: List<EmailAddress>, private val counts: Map<String, Int>) {
    private val sorted = counts.keys.sorted()

    /** Words that start with [prefix] and are longer than it, commonest first. */
    fun completions(prefix: String): List<String> {
        val start = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it + 1 }
        val end = (start until sorted.size).firstOrNull { !sorted[it].startsWith(prefix) } ?: sorted.size
        return sorted.subList(start, end).sortedByDescending { counts.getValue(it) }
    }
}
