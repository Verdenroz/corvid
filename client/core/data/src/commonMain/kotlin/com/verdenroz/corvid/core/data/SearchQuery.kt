package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock
import kotlin.time.Instant

private const val ANYWHERE = "anywhere"
private val FIELDS = setOf("from", "to", "cc", "subject")
private val OPERATORS = FIELDS + setOf("has", "is", "in", "before", "after", "older_than", "newer_than")
private val MAILBOX_ROLES =
    mapOf(INBOX to INBOX, "sent" to "sent", "drafts" to "drafts", TRASH to TRASH, JUNK to JUNK, "spam" to JUNK, ARCHIVE to ARCHIVE)
private val DATE = Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})")
private val AGE = Regex("(\\d{1,4})([dmy])")

/** Where a search looks: everywhere but Trash and Junk unless the query names a mailbox or says `in:anywhere`. */
enum class SearchScope { OutsideDiscarded, Anywhere, Named }

sealed interface SearchTerm {
    /** A whole word in the subject, body, sender or recipients. */
    data class Word(val text: String) : SearchTerm

    data class Phrase(val text: String) : SearchTerm

    /** `from`, `to`, `cc` or `subject`, as RFC 8621 names the filter property. */
    data class Field(val property: String, val value: String) : SearchTerm

    data object HasAttachment : SearchTerm

    data class Keyword(val keyword: String, val present: Boolean) : SearchTerm

    data class Before(val instant: Instant) : SearchTerm

    data class After(val instant: Instant) : SearchTerm

    data class InMailbox(val role: String) : SearchTerm

    data class Not(val term: SearchTerm) : SearchTerm
}

/**
 * A search box query in Gmail's operator syntax. [completing] is the bare word the query ends with,
 * still being typed, which is matched against its completions rather than as a whole word.
 */
data class SearchQuery(val terms: List<SearchTerm>, val completing: String?, val scope: SearchScope) {
    companion object {
        fun parse(
            text: String,
            now: Instant = Clock.System.now(),
            zone: TimeZone = TimeZone.currentSystemDefault(),
        ): SearchQuery {
            val tokens = tokenize(text)
            val last = tokens.lastOrNull()?.takeIf { it.isBareWord() }
            val parsed = (if (last == null) tokens else tokens.dropLast(1)).mapNotNull { term(it, now, zone) }
            val named = parsed.any { it is SearchTerm.InMailbox }
            val anywhere = tokens.any { it.equals("in:$ANYWHERE", ignoreCase = true) }
            val scope =
                when {
                    named -> SearchScope.Named
                    anywhere -> SearchScope.Anywhere
                    else -> SearchScope.OutsideDiscarded
                }
            return SearchQuery(parsed, last, scope)
        }
    }
}

/** [text] with `in:anywhere` added or removed, so the field shows the query the list is answering. */
fun searchAnywhere(
    text: String,
    anywhere: Boolean,
): String {
    val kept = tokenize(text).filterNot { it.equals("in:$ANYWHERE", ignoreCase = true) }
    return (if (anywhere) kept + "in:$ANYWHERE" else kept).joinToString(" ")
}

/**
 * The RFC 8621 FilterCondition for [query], or null when it names a mailbox this account lacks,
 * which nothing can match. [complete] expands the word still being typed.
 */
internal suspend fun SearchQuery.toFilter(
    mailboxId: suspend (String) -> String?,
    complete: suspend (String) -> JsonObject,
): JsonObject? {
    val conditions = terms.map { it.condition(mailboxId) ?: return null } + listOfNotNull(completing?.let { complete(it) })
    return when (conditions.size) {
        0 -> JsonObject(emptyMap())
        1 -> conditions.single()
        else -> operator("AND", conditions)
    }
}

private suspend fun SearchTerm.condition(mailboxId: suspend (String) -> String?): JsonObject? =
    when (this) {
        is SearchTerm.Word -> buildJsonObject { put("text", text) }
        is SearchTerm.Phrase -> buildJsonObject { put("text", "\"$text\"") }
        is SearchTerm.Field -> buildJsonObject { put(property, value) }
        is SearchTerm.HasAttachment -> buildJsonObject { put("hasAttachment", true) }
        is SearchTerm.Keyword -> buildJsonObject { put(if (present) "hasKeyword" else "notKeyword", keyword) }
        is SearchTerm.Before -> buildJsonObject { put("before", instant.toString()) }
        is SearchTerm.After -> buildJsonObject { put("after", instant.toString()) }
        is SearchTerm.InMailbox -> mailboxId(role)?.let { id -> buildJsonObject { put("inMailbox", id) } }
        is SearchTerm.Not -> term.condition(mailboxId)?.let { operator("NOT", listOf(it)) }
    }

private fun operator(
    name: String,
    conditions: List<JsonObject>,
) = buildJsonObject {
    put("operator", name)
    putJsonArray("conditions") { conditions.forEach { add(it) } }
}

/** Splits on whitespace outside double quotes; an unclosed quote runs to the end. */
private fun tokenize(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (char in text) {
        when {
            char == '"' -> {
                quoted = !quoted
                current.append(char)
            }
            char.isWhitespace() && !quoted -> {
                if (current.isNotEmpty()) tokens += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

private fun String.isBareWord() = !startsWith("-") && '"' !in this && ':' !in this

private fun term(
    token: String,
    now: Instant,
    zone: TimeZone,
): SearchTerm? {
    if (token.equals("in:$ANYWHERE", ignoreCase = true)) return null
    val negated = token.length > 1 && token.startsWith("-")
    val body = if (negated) token.drop(1) else token
    if (body.endsWith(':') && body.dropLast(1).lowercase() in OPERATORS) return null
    val positive = phrase(body) ?: operatorTerm(body, now, zone) ?: SearchTerm.Word(body)
    if (positive is SearchTerm.Phrase && positive.text.isBlank()) return null
    return if (negated) SearchTerm.Not(positive) else positive
}

private fun phrase(body: String): SearchTerm.Phrase? = if (body.startsWith('"')) SearchTerm.Phrase(unquote(body)) else null

private fun unquote(text: String) = text.removePrefix("\"").removeSuffix("\"").trim()

private fun operatorTerm(
    body: String,
    now: Instant,
    zone: TimeZone,
): SearchTerm? {
    val name = body.substringBefore(':', "").lowercase()
    val value = unquote(body.substringAfter(':'))
    if (name.isEmpty() || value.isEmpty()) return null
    val lower = value.lowercase()
    return when (name) {
        in FIELDS -> SearchTerm.Field(name, value)
        "has" -> SearchTerm.HasAttachment.takeIf { lower == "attachment" }
        "is" -> keyword(lower)
        "in" -> MAILBOX_ROLES[lower]?.let(SearchTerm::InMailbox)
        "before" -> date(value, zone)?.let(SearchTerm::Before)
        "after" -> date(value, zone)?.let(SearchTerm::After)
        "older_than" -> age(lower, now, zone)?.let(SearchTerm::Before)
        "newer_than" -> age(lower, now, zone)?.let(SearchTerm::After)
        else -> null
    }
}

private fun keyword(value: String): SearchTerm.Keyword? =
    when (value) {
        "unread" -> SearchTerm.Keyword(CorvidKeywords.SEEN, present = false)
        "read" -> SearchTerm.Keyword(CorvidKeywords.SEEN, present = true)
        "starred" -> SearchTerm.Keyword(CorvidKeywords.FLAGGED, present = true)
        else -> null
    }

/** The start of that day where the user is, as Gmail reads a date. */
private fun date(
    value: String,
    zone: TimeZone,
): Instant? {
    val (year, month, day) = DATE.matchEntire(value)?.destructured ?: return null
    val local = runCatching { LocalDate(year.toInt(), month.toInt(), day.toInt()) }.getOrNull() ?: return null
    return local.atStartOfDayIn(zone)
}

/** RFC 8621 UTCDate carries no fraction of a second, so the result is cut to whole seconds. */
private fun age(
    value: String,
    now: Instant,
    zone: TimeZone,
): Instant? {
    val (count, unit) = AGE.matchEntire(value)?.destructured ?: return null
    val step =
        when (unit) {
            "d" -> DateTimeUnit.DAY
            "m" -> DateTimeUnit.MONTH
            else -> DateTimeUnit.YEAR
        }
    return Instant.fromEpochSeconds(now.minus(count.toInt(), step, zone).epochSeconds)
}
