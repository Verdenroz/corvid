package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.reason
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.setErrorReason
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.text.SystemTextResolver
import com.verdenroz.corvid.core.text.TextResolver
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.forwarded_message
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock

/** Why this account cannot send at all, as opposed to a server refusing one message. */
sealed interface SendUnavailable : StartResult, SendResult, DraftSaveResult {
    data object NoIdentity : SendUnavailable

    data class NoMailbox(val role: String) : SendUnavailable
}

sealed interface StartResult {
    data class Ready(val draft: Draft) : StartResult

    /** The message a reply or forward answers was deleted or moved before compose opened. */
    data object OriginalGone : StartResult

    /** The draft was sent, discarded or never was one. */
    data object DraftGone : StartResult
}

sealed interface DraftSaveResult {
    data class Saved(val emailId: String) : DraftSaveResult

    data class Rejected(val reason: String) : DraftSaveResult
}

sealed interface SendResult {
    data object Sent : SendResult

    /** The server refused the draft or its submission; nothing was delivered. */
    data class Rejected(val reason: String) : SendResult
}

/** The draft is from an alias with no identity any more, as a disabled one has; it is never sent from the main address instead. */
data class AliasGone(val email: String) : SendResult, DraftSaveResult

private sealed interface Loaded {
    data class Ready(val identity: Identity, val ownEmails: Set<String>, val draftsId: String, val sentId: String) : Loaded

    data class Unavailable(val reason: SendUnavailable) : Loaded
}

private inline fun <R> Loaded.fold(
    onUnavailable: (SendUnavailable) -> R,
    onReady: (Loaded.Ready) -> R,
): R =
    when (this) {
        is Loaded.Unavailable -> onUnavailable(reason)
        is Loaded.Ready -> onReady(this)
    }

/**
 * The main identity and the Drafts and Sent mailboxes are fetched once, in one
 * request, and kept for as long as the repository lives. A draft from an alias
 * reads the identities again at each save and send, since disabling the alias
 * removes its identity and enabling it again makes a new one. A message is always a
 * server draft before it is sent: [save] files it, [send] submits that draft
 * and moves it to Sent, so a refused submission leaves the draft in place. A
 * refusal comes back as [SendResult.Rejected] or [DraftSaveResult.Rejected] rather
 * than a thrown error: the caller keeps the text and shows why.
 */
class ComposeRepository(
    private val jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
    private val textResolver: TextResolver = SystemTextResolver,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var ready: Loaded.Ready? = null

    suspend fun start(intent: ComposeIntent): StartResult =
        load().fold({ it }) { sending ->
            when (intent) {
                is ComposeIntent.New -> StartResult.Ready(Draft())
                is ComposeIntent.To -> StartResult.Ready(Draft(to = listOf(EmailAddress(null, intent.address)), subject = intent.subject))
                is ComposeIntent.Reply ->
                    withOriginal(intent.emailId) {
                        replyDraft(it, sending.ownEmails, intent.all, textResolver.resolve(replyAttribution(it, zone, clock.now())))
                    }
                is ComposeIntent.Draft -> loadDraft(intent.emailId, sending.identity.email)
                is ComposeIntent.Forward ->
                    withOriginal(intent.emailId) {
                        forwardDraft(
                            it,
                            textResolver.resolve(uiText(Res.string.forwarded_message)),
                            textResolver.resolve(stamp(it.receivedAt, zone, clock.now())),
                        )
                    }
            }
        }

    private suspend fun withOriginal(
        emailId: String,
        draft: suspend (EmailDetail) -> Draft,
    ): StartResult =
        when (val lookup = mailboxRepository.getEmail(emailId)) {
            is EmailLookup.Found -> StartResult.Ready(draft(lookup.detail))
            is EmailLookup.NotFound -> StartResult.OriginalGone
        }

    private suspend fun loadDraft(
        emailId: String,
        mainEmail: String,
    ): StartResult {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { add(JsonPrimitive(emailId)) }
                putJsonArray("properties") { DRAFT_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
                put("fetchTextBodyValues", true)
            }
        val found = jmapClient.callOne("Email/get", arguments).getValue("list").jsonArray.firstOrNull()?.jsonObject
        return if (found != null && CorvidKeywords.DRAFT in found.getValue("keywords").jsonObject) {
            StartResult.Ready(found.toDraft(mainEmail))
        } else {
            StartResult.DraftGone
        }
    }

    /**
     * Files [draft] as a new server draft, then destroys [replacing], since an email cannot be edited
     * in place. The destroy waits for the create so a refused save never costs the old draft.
     */
    suspend fun save(
        draft: Draft,
        replacing: String?,
    ): DraftSaveResult =
        load().fold({ it }) { sending ->
            val identity = draft.from?.let { from -> sending.sendingAs(from) ?: return@fold AliasGone(from) } ?: sending.identity
            val arguments = createEmail(identity, draft, sending.draftsId)
            val outcome = jmapClient.call(listOf(Invocation("Email/set", arguments, "c0"))).getValue("c0")
            outcome.refusal("draft")?.let { return@fold DraftSaveResult.Rejected(it) }
            try {
                replacing?.let { discard(it) }
            } catch (_: JmapException) {
                // A stray older draft is harmless; losing the new one is not.
            }
            DraftSaveResult.Saved(outcome.createdDraftId())
        }

    /** [from] is the saved draft's [Draft.from], which picks the identity the submission names. */
    suspend fun send(
        emailId: String,
        from: String?,
    ): SendResult =
        load().fold({ it }) { sending ->
            val identity = from?.let { sending.sendingAs(it) ?: return@fold AliasGone(it) } ?: sending.identity
            val arguments = submit(identity, emailId, sending.draftsId, sending.sentId)
            val outcome = jmapClient.call(listOf(Invocation("EmailSubmission/set", arguments, "c0"))).getValue("c0")
            outcome.refusal("submission")?.let { SendResult.Rejected(it) } ?: SendResult.Sent
        }

    /** People to suggest as recipients, best first. */
    suspend fun contacts(): List<Contact> = mailboxRepository.contacts()

    /** Uploads [file] as a blob for a draft to name; one over the session's `maxSizeUpload` is refused before it is read. */
    suspend fun upload(file: PickedFile): UploadResult {
        jmapClient.session.maxSizeUpload?.takeIf { file.size > it }?.let { return UploadResult.TooLarge(it) }
        val blobId = jmapClient.uploadBlob(file.read(), file.type)
        return UploadResult.Uploaded(Attachment(blobId, file.name, file.type, file.size))
    }

    suspend fun discard(emailId: String) {
        val arguments = buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(emailId)) } }
        jmapClient.callOne("Email/set", arguments)
    }

    /** Identity/get lists at most 500, newest first, so an alias older than that reads as gone. */
    private suspend fun Loaded.Ready.sendingAs(email: String): Identity? =
        if (email.equals(identity.email, ignoreCase = true)) {
            identity
        } else {
            jmapClient.callOne("Identity/get", JsonObject(emptyMap())).getValue("list").jsonArray
                .map { it.jsonObject.toIdentity() }
                .firstOrNull { it.email.equals(email, ignoreCase = true) }
        }

    private suspend fun load(): Loaded =
        mutex.withLock {
            ready ?: fetch().also { if (it is Loaded.Ready) ready = it }
        }

    private suspend fun fetch(): Loaded {
        val outcomes =
            jmapClient.call(
                listOf(
                    Invocation("Identity/get", JsonObject(emptyMap()), "c0"),
                    Invocation(
                        "Mailbox/get",
                        buildJsonObject { putJsonArray("properties") { listOf("id", "role").forEach { add(JsonPrimitive(it)) } } },
                        "c1",
                    ),
                ),
            )
        val identities = outcomes.getValue("c0").resultOrThrow().getValue("list").jsonArray.map { it.jsonObject.toIdentity() }
        val identity = identities.mainIdentity(jmapClient.session.accountName) ?: return Loaded.Unavailable(SendUnavailable.NoIdentity)
        val roles =
            outcomes.getValue("c1").resultOrThrow().getValue("list").jsonArray
                .map { it.jsonObject }
                .associate { it["role"]?.jsonPrimitive?.contentOrNull to it.getValue("id").jsonPrimitive.content }
        val draftsId = roles[DRAFTS] ?: return Loaded.Unavailable(SendUnavailable.NoMailbox(DRAFTS))
        val sentId = roles[SENT] ?: return Loaded.Unavailable(SendUnavailable.NoMailbox(SENT))
        return Loaded.Ready(identity, identities.map { it.email }.toSet(), draftsId, sentId)
    }
}

/** The identity for the account's own address, which the session names in full; an alias is never the sender. */
internal fun List<Identity>.mainIdentity(accountName: String?): Identity? =
    accountName?.let { name -> firstOrNull { it.email.equals(name, ignoreCase = true) } }

internal fun JsonObject.toIdentity() =
    Identity(
        id = getValue("id").jsonPrimitive.content,
        name = get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
        email = getValue("email").jsonPrimitive.content,
    )

private fun MethodOutcome.refusal(creationId: String): String? =
    when (this) {
        is MethodOutcome.Failure -> error.reason
        is MethodOutcome.Success -> result.setErrorReason("notCreated", creationId)
    }

private fun MethodOutcome.createdDraftId(): String =
    (this as MethodOutcome.Success).result.getValue("created").jsonObject.getValue("draft").jsonObject.getValue("id").jsonPrimitive.content
