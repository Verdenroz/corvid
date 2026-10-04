package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Provenance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val AUTHENTICATION_RESULTS = "header:Authentication-Results:asText:all"
internal const val CORVID_REWRITE = "header:X-Corvid-Rewrite:asText:all"
private const val LIST_ID = "header:List-Id:asText"
private const val LIST_UNSUBSCRIBE = "header:List-Unsubscribe:asURLs"
private const val AUTO_SUBMITTED = "header:Auto-Submitted:asText"

internal val PROVENANCE_PROPERTIES = listOf(AUTHENTICATION_RESULTS, CORVID_REWRITE, LIST_ID, LIST_UNSUBSCRIBE, AUTO_SUBMITTED)

internal fun JsonObject.provenance(
    from: EmailAddress?,
    ownDomain: String?,
): Provenance {
    val unsubscribe = texts(LIST_UNSUBSCRIBE)
    return Provenance(
        seal = readSeal(texts(AUTHENTICATION_RESULTS)),
        rewrite = readRewrite(texts(CORVID_REWRITE)),
        sender = senderKind(from, SenderHeaders(text(LIST_ID), unsubscribe, text(AUTO_SUBMITTED)), ownDomain),
        unsubscribe = unsubscribe,
    )
}

internal fun JsonObject.texts(property: String): List<String> = (get(property) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

private fun JsonObject.text(property: String): String? = get(property)?.jsonPrimitive?.contentOrNull
