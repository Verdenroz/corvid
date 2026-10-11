package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

    /** Every typed word must match; the last, still being typed, may match any of its completions instead. */
    suspend fun searchFilter(text: String): JsonObject {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return buildJsonObject { put("text", text) }
        val conditions = words.dropLast(1).map(::word) + completions(words.last())
        return conditions.singleOrNull() ?: allOf(conditions)
    }

    private suspend fun completions(fragment: String): JsonObject {
        if (fragment.length < MIN_FRAGMENT) return word(fragment)
        val known = known()
        val addresses = known.addresses.filter { it.email.contains(fragment, true) || it.name?.contains(fragment, true) == true }
        val emails = addresses.map { it.email.lowercase() }.distinct().take(MAX_EXPANSIONS)
        val lower = fragment.lowercase()
        val words = known.completions(lower).take(MAX_EXPANSIONS)
        if (emails.isEmpty() && words.isEmpty()) return word(fragment)
        return buildJsonObject {
            put("operator", "OR")
            putJsonArray("conditions") {
                add(word(fragment))
                words.forEach { add(word(it)) }
                emails.forEach { email ->
                    addJsonObject { put("from", email) }
                    addJsonObject { put("to", email) }
                }
            }
        }
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
private class Known(val addresses: List<EmailAddress>, counts: Map<String, Int>) {
    private val sorted = counts.keys.sorted()
    private val count = counts

    /** Words that start with [prefix] and are longer than it, commonest first. */
    fun completions(prefix: String): List<String> {
        val start = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it + 1 }
        val end = (start until sorted.size).firstOrNull { !sorted[it].startsWith(prefix) } ?: sorted.size
        return sorted.subList(start, end).sortedByDescending { count.getValue(it) }
    }
}

private fun word(text: String) = buildJsonObject { put("text", text) }

private fun allOf(conditions: List<JsonObject>) =
    buildJsonObject {
        put("operator", "AND")
        putJsonArray("conditions") { conditions.forEach { add(it) } }
    }
