package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.Provenance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

private const val SAMPLE = 500
private val SAMPLE_PROPERTIES =
    listOf("from", "to", "cc", "subject", "preview", "receivedAt", "keywords", "mailboxIds") + PROVENANCE_PROPERTIES

/** One message of the sample, with only what search completion, recipient suggestions and the [Directory] read. */
internal data class SampledEmail(
    val from: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val subject: String?,
    val preview: String?,
    val receivedAt: Instant,
    val keywords: Set<String>,
    val mailboxIds: Set<String>,
    val provenance: Provenance,
) {
    val draft: Boolean get() = CorvidKeywords.DRAFT in keywords
}

/** The newest [SAMPLE] messages, fetched by whichever reader asks first and kept until a reader asks for them [fresh]. */
internal class MailSample(
    private val jmapClient: JmapClient,
    private val ownDomain: suspend () -> String?,
) {
    private val lock = Mutex()
    private var emails: List<SampledEmail>? = null

    suspend fun emails(fresh: Boolean = false): List<SampledEmail> =
        lock.withLock { emails.takeUnless { fresh } ?: fetch().also { emails = it } }

    private suspend fun fetch(): List<SampledEmail> {
        val query =
            buildJsonObject {
                put("sort", NEWEST_FIRST)
                put("limit", SAMPLE)
            }
        val domain = ownDomain()
        return jmapClient.queryAndGet(query, SAMPLE_PROPERTIES).get.resultOrThrow()
            .getValue("list").jsonArray.map { it.jsonObject }
            .map { email ->
                val from = email.addressList("from")
                SampledEmail(
                    from = from,
                    to = email.addressList("to"),
                    cc = email.addressList("cc"),
                    subject = email["subject"]?.jsonPrimitive?.contentOrNull,
                    preview = email["preview"]?.jsonPrimitive?.contentOrNull,
                    receivedAt = Instant.parse(email.getValue("receivedAt").jsonPrimitive.content),
                    keywords = email["keywords"]?.jsonObject?.keys.orEmpty(),
                    mailboxIds = email["mailboxIds"]?.jsonObject?.keys.orEmpty(),
                    provenance = email.provenance(from.firstOrNull(), domain),
                )
            }
    }
}
