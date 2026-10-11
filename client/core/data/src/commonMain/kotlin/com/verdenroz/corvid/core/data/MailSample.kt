package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Instant

private const val SAMPLE = 500
private val SAMPLE_PROPERTIES = listOf("from", "to", "cc", "subject", "preview", "receivedAt", "keywords")

/** One message of the sample, with only what search completion and recipient suggestions read. */
internal data class SampledEmail(
    val from: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val subject: String?,
    val preview: String?,
    val receivedAt: Instant,
    val draft: Boolean,
)

/** The newest [SAMPLE] messages, fetched by whichever reader asks first and kept for the session. */
internal class MailSample(private val jmapClient: JmapClient) {
    private val lock = Mutex()
    private var emails: List<SampledEmail>? = null

    suspend fun emails(): List<SampledEmail> = lock.withLock { emails ?: fetch().also { emails = it } }

    private suspend fun fetch(): List<SampledEmail> {
        val query =
            buildJsonObject {
                putJsonArray("sort") {
                    addJsonObject {
                        put("property", "receivedAt")
                        put("isAscending", false)
                    }
                }
                put("limit", SAMPLE)
            }
        return jmapClient.queryAndGet(query, SAMPLE_PROPERTIES).get.resultOrThrow()
            .getValue("list").jsonArray.map { it.jsonObject }
            .map { email ->
                SampledEmail(
                    from = email.addressList("from"),
                    to = email.addressList("to"),
                    cc = email.addressList("cc"),
                    subject = email["subject"]?.jsonPrimitive?.contentOrNull,
                    preview = email["preview"]?.jsonPrimitive?.contentOrNull,
                    receivedAt = Instant.parse(email.getValue("receivedAt").jsonPrimitive.content),
                    draft = email["keywords"]?.jsonObject?.containsKey(CorvidKeywords.DRAFT) == true,
                )
            }
    }
}
