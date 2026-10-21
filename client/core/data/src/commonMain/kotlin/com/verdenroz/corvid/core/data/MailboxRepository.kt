package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.html.CID
import com.verdenroz.corvid.core.html.flattenHtml
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.jmap.resultReference
import com.verdenroz.corvid.core.jmap.setErrorReason
import com.verdenroz.corvid.core.model.Attachment
import com.verdenroz.corvid.core.model.Conversation
import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.model.EmailDetail
import com.verdenroz.corvid.core.model.EmailSummary
import com.verdenroz.corvid.core.model.HeaderField
import com.verdenroz.corvid.core.model.HtmlBody
import com.verdenroz.corvid.core.model.Identity
import com.verdenroz.corvid.core.model.Mailbox
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Instant

private const val PAGE_SIZE = 50
private const val SET_CHUNK = 250
private const val OWN_STATES = 16
const val INBOX = "inbox"
const val SENT = "sent"
const val DRAFTS = "drafts"
const val TRASH = "trash"
const val ARCHIVE = "archive"
const val JUNK = "junk"
private val MAILBOX_PROPERTIES = listOf("id", "name", "role", "parentId", "totalEmails", "unreadEmails")
private const val MAX_BODY_VALUE_BYTES = 262_144

// Stalwart prepends its own Delivered-To, so only the first is the server's; a later one came with the message.
private const val DELIVERED_TO = "header:Delivered-To:asText:all"
internal val EMAIL_PROPERTIES =
    listOf("id", "threadId", "subject", "from", "receivedAt", "keywords", "preview", "hasAttachment") + PROVENANCE_PROPERTIES
private val EMAIL_DETAIL_PROPERTIES =
    listOf(
        "id", "subject", "from", "to", "cc", "replyTo", "messageId", "references", "receivedAt", "keywords", "preview",
        "textBody", "htmlBody", "bodyValues", "mailboxIds", "attachments", DELIVERED_TO,
    ) + PROVENANCE_PROPERTIES

/** What a list shows: one mailbox, every starred message wherever it is filed, or every message matching a text search. */
sealed interface MailFilter {
    /** Whether a list shows one row per thread; a search, and a mailbox of drafts opened one by one, do not. */
    val threaded: Boolean get() = true

    data class InMailbox(val mailboxId: String, override val threaded: Boolean = true) : MailFilter

    data object Starred : MailFilter

    data class Search(val text: String) : MailFilter {
        override val threaded: Boolean get() = false
    }
}

sealed interface EmailPage {
    /** [total] counts every match, and is known only for the first page of a search. */
    data class Found(val conversations: List<Conversation>, val queryState: String, val total: Int? = null) : EmailPage

    data object AnchorNotFound : EmailPage
}

/** Keywords set (true) or cleared (false) on one message, as the server accepted them. */
data class KeywordPatch(val emailId: String, val patch: Map<String, Boolean>) {
    fun applyTo(keywords: Set<String>): Set<String> = keywords + patch.filterValues { it }.keys - patch.filterValues { !it }.keys

    fun applyTo(conversation: Conversation): Conversation {
        if (emailId !in conversation.emailIds) return conversation
        val latest = conversation.latest
        val unreadIds =
            when (patch[CorvidKeywords.SEEN]) {
                true -> conversation.unreadIds - emailId
                false -> conversation.unreadIds + emailId
                null -> conversation.unreadIds
            }
        return conversation.copy(
            latest = if (emailId == latest.id) latest.copy(keywords = applyTo(latest.keywords)) else latest,
            unreadIds = unreadIds,
        )
    }
}

/** One Inbox message as a new-mail check sees it: no sender, subject or body, only when it arrived and whether it is read. */
data class InboxArrival(val id: String, val receivedAt: Instant, val seen: Boolean)

sealed interface EmailLookup {
    data class Found(val detail: EmailDetail) : EmailLookup

    /** The message was deleted or moved between the inbox list and opening it. */
    data object NotFound : EmailLookup
}

sealed interface ThreadLookup {
    /** [messages] are the whole thread, oldest first; [threadId] is its JMAP id. */
    data class Found(val threadId: String, val messages: List<EmailDetail>) : ThreadLookup

    data object NotFound : ThreadLookup
}

/**
 * Query and get run as one JMAP request, the get's `ids`
 * resolved server-side from the query's result via a [resultReference].
 * Whether an anchor lost meaning (queryState changed) is for the caller to
 * decide by comparing to the queryState it already holds; this repository
 * is stateless across pages.
 */
class MailboxRepository(
    private val jmapClient: JmapClient,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val sample = MailSample(jmapClient, ::ownDomain)
    private val vocabulary = SearchVocabulary(sample)
    private val identityLock = Mutex()
    private val rolesLock = Mutex()
    private var identities: List<Identity>? = null
    private var roleIds: Map<String, String>? = null
    private var lastMailboxes: List<Mailbox>? = null
    private val _keywordPatches = MutableSharedFlow<KeywordPatch>(extraBufferCapacity = 16)

    /** Every patch [updateKeywords] got through, so a list already on screen can apply it without refetching. */
    val keywordPatches: SharedFlow<KeywordPatch> = _keywordPatches

    private val _moves = MutableSharedFlow<MoveEvent>(extraBufferCapacity = 16)

    /** Every archive, trash, undo and delete, as it starts and as it ends, so each list can hide and then refetch its own rows. */
    val moves: SharedFlow<MoveEvent> = _moves

    private val ownStates = MutableStateFlow<List<String>>(emptyList())

    /** Whether [state] is an Email state one of this client's own recent writes produced, so a push of it brings nothing new. */
    fun isOwnEmailState(state: String): Boolean = state in ownStates.value

    /** The one path images in delivered mail may load from: the milter rewrites each remote image to it. */
    val imageProxyBase: String get() = "${jmapClient.webBase}/i/"

    /** Never fetches outside [imageProxyBase], whatever the caller passes. */
    suspend fun proxiedImage(url: String): ByteArray? = if (url.startsWith(imageProxyBase)) jmapClient.fetchPublic(url) else null

    /** An image [email]'s HTML draws: a `cid:` part of the message from the server, anything else only through [proxiedImage]. */
    suspend fun messageImage(
        email: EmailDetail,
        url: String,
    ): ByteArray? {
        if (!url.startsWith(CID, ignoreCase = true)) return proxiedImage(url)
        val part = email.inlineImages[url.drop(CID.length).lowercase()] ?: return null
        return try {
            download(part)
        } catch (e: JmapException) {
            null
        }
    }

    /** The attachment's bytes, as the server stores the decoded part. */
    suspend fun download(attachment: Attachment): ByteArray =
        jmapClient.downloadBlob(attachment.blobId, attachment.fileName(), attachment.type)

    /** Every mailbox with its counts, fetched now; [knownMailboxes] answers with this list until the next call. */
    suspend fun mailboxes(): List<Mailbox> =
        fetchMailboxes(ids = null).also { boxes ->
            lastMailboxes = boxes
            rolesLock.withLock { if (roleIds == null) roleIds = boxes.roleIds() }
        }

    /** The last [mailboxes] answer, fetched only when there is none, so its counts may be stale. */
    suspend fun knownMailboxes(): List<Mailbox> = lastMailboxes ?: mailboxes()

    /** Counts change with every message, so this always asks the server; for the id alone use [mailboxId]. */
    suspend fun findMailboxByRole(role: String): Mailbox? {
        val ids = rolesLock.withLock { roleIds } ?: return mailboxes().firstOrNull { it.role == role }
        val id = ids[role] ?: return null
        return fetchMailboxes(listOf(id)).firstOrNull()
    }

    private suspend fun fetchMailboxes(ids: List<String>?): List<Mailbox> {
        val arguments =
            buildJsonObject {
                ids?.let { putJsonArray("ids") { it.forEach { id -> add(JsonPrimitive(id)) } } }
                putJsonArray("properties") { MAILBOX_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
            }
        return jmapClient.callOne("Mailbox/get", arguments).getValue("list").jsonArray.map { it.jsonObject.toMailbox() }
    }

    /**
     * The newest Inbox messages, read or not, leaving out any from the account's own address, so mail sent to
     * oneself never counts as arriving. Fetches ids, arrival times and keywords only.
     */
    suspend fun inboxArrivals(): List<InboxArrival> {
        val inbox = mailboxId(INBOX) ?: return emptyList()
        val own = identity()?.email
        val notOwn = own?.let { operator("NOT", listOf(condition("from", it))) }
        val query =
            buildJsonObject {
                put("filter", operator("AND", listOfNotNull(condition("inMailbox", inbox), notOwn)))
                put("sort", NEWEST_FIRST)
                put("limit", PAGE_SIZE)
            }
        val found = jmapClient.queryAndGet(query, listOf("id", "receivedAt", "keywords")).get.resultOrThrow()
        return found.getValue("list").jsonArray.map {
            val email = it.jsonObject
            InboxArrival(
                id = email.getValue("id").jsonPrimitive.content,
                receivedAt = Instant.parse(email.getValue("receivedAt").jsonPrimitive.content),
                seen = CorvidKeywords.SEEN in email.getValue("keywords").jsonObject.keys,
            )
        }
    }

    /** The id of the mailbox with [role], fetched once: roles do not move while the app is open. */
    suspend fun mailboxId(role: String): String? =
        rolesLock.withLock {
            val ids = roleIds ?: fetchMailboxes(ids = null).also { lastMailboxes = it }.roleIds().also { roleIds = it }
            ids[role]
        }

    /** Whether [mailboxIds] is Trash alone or Junk alone, where a message can be deleted for good. */
    suspend fun discardedOnly(mailboxIds: Set<String>): Boolean {
        val discarded = listOfNotNull(mailboxId(TRASH), mailboxId(JUNK)).map(::setOf)
        return mailboxIds in discarded
    }

    /** Lists conversations, newest first, unless the filter is not [MailFilter.threaded]; then each row is one message. */
    suspend fun page(
        filter: MailFilter,
        anchor: String?,
    ): EmailPage {
        val grouped = filter.threaded
        val condition = serverFilter(filter) ?: return EmailPage.Found(emptyList(), queryState = "", total = 0)
        val queryArguments =
            buildJsonObject {
                put("filter", condition)
                put("sort", NEWEST_FIRST)
                put("collapseThreads", grouped)
                put("limit", PAGE_SIZE)
                anchor?.let {
                    put("anchor", it)
                    put("anchorOffset", 1)
                }
                if (anchor == null && filter is MailFilter.Search) put("calculateTotal", true)
            }
        return if (grouped) conversationPage(queryArguments) else messagePage(queryArguments)
    }

    /**
     * Starred and search leave out Trash and Junk, as mail deleted or marked spam is no longer the user's to find there,
     * unless the search names a mailbox or says `in:anywhere`. Null when the search names a mailbox this account lacks.
     */
    private suspend fun serverFilter(filter: MailFilter): JsonObject? =
        when (filter) {
            is MailFilter.InMailbox -> condition("inMailbox", filter.mailboxId)
            is MailFilter.Starred -> outsideDiscarded(condition("hasKeyword", CorvidKeywords.FLAGGED))
            is MailFilter.Search -> searchFilter(SearchQuery.parse(filter.text, clock.now(), zone))
        }

    private suspend fun searchFilter(query: SearchQuery): JsonObject? {
        val condition = query.toFilter(::mailboxId, vocabulary::completions) ?: return null
        return if (query.scope == SearchScope.OutsideDiscarded) outsideDiscarded(condition) else condition
    }

    private suspend fun outsideDiscarded(condition: JsonObject): JsonObject {
        val discarded = listOfNotNull(mailboxId(TRASH), mailboxId(JUNK))
        if (discarded.isEmpty()) return condition
        val outside = buildJsonObject { putJsonArray("inMailboxOtherThan") { discarded.forEach { add(JsonPrimitive(it)) } } }
        return operator("AND", listOf(condition, outside))
    }

    private suspend fun messagePage(queryArguments: JsonObject): EmailPage {
        val outcomes = jmapClient.queryAndGet(queryArguments, EMAIL_PROPERTIES)
        if (outcomes.query.anchorNotFound()) return EmailPage.AnchorNotFound
        val domain = ownDomain()
        val emails = outcomes.get.resultOrThrow().getValue("list").jsonArray.map { it.jsonObject.toEmailSummary(domain) }
        val conversations =
            emails.map { Conversation(it, listOf(it.id), if (CorvidKeywords.SEEN in it.keywords) emptySet() else setOf(it.id)) }
        val found = outcomes.query.resultOrThrow()
        return EmailPage.Found(conversations, found.queryState(), found["total"]?.jsonPrimitive?.int)
    }

    private suspend fun conversationPage(queryArguments: JsonObject): EmailPage {
        val outcomes = jmapClient.queryConversations(queryArguments, EMAIL_PROPERTIES)
        if (outcomes.query.anchorNotFound()) return EmailPage.AnchorNotFound
        val domain = ownDomain()
        val latest = outcomes.latest.resultOrThrow().getValue("list").jsonArray.map { it.jsonObject.toEmailSummary(domain) }
        val threads =
            outcomes.threads.resultOrThrow().getValue("list").jsonArray.associate {
                it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.idList("emailIds")
            }
        val keywords =
            outcomes.members.resultOrThrow().getValue("list").jsonArray.associate {
                it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("keywords").jsonObject.keys
            }
        val conversations =
            latest.map { summary ->
                val emailIds = threads.getValue(summary.threadId)
                Conversation(summary, emailIds, emailIds.filterTo(mutableSetOf()) { CorvidKeywords.SEEN !in keywords.getValue(it) })
            }
        return EmailPage.Found(conversations, outcomes.query.resultOrThrow().queryState())
    }

    /** The thread holding [emailId], every message with its body, oldest first. */
    suspend fun thread(emailId: String): ThreadLookup {
        val arguments =
            buildJsonObject {
                putJsonArray("properties") { EMAIL_DETAIL_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
                put("fetchTextBodyValues", true)
                put("fetchHTMLBodyValues", true)
                put("maxBodyValueBytes", MAX_BODY_VALUE_BYTES)
            }
        val outcomes = jmapClient.fetchThread(emailId, arguments)
        if (outcomes.email.resultOrThrow().getValue("notFound").jsonArray.isNotEmpty()) return ThreadLookup.NotFound
        val thread = outcomes.thread.resultOrThrow().getValue("list").jsonArray.single().jsonObject
        val order = thread.idList("emailIds")
        val domain = ownDomain()
        val list = outcomes.messages.resultOrThrow().getValue("list").jsonArray.map { it.jsonObject }
        val aliases = aliasEmails(list)
        val messages = list.map { it.toEmailDetail(domain, aliases) }
        return ThreadLookup.Found(thread.getValue("id").jsonPrimitive.content, messages.sortedBy { order.indexOf(it.id) })
    }

    suspend fun getEmail(id: String): EmailLookup {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { add(JsonPrimitive(id)) }
                putJsonArray("properties") { EMAIL_DETAIL_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
                put("fetchTextBodyValues", true)
                put("fetchHTMLBodyValues", true)
                put("maxBodyValueBytes", MAX_BODY_VALUE_BYTES)
            }
        val result = jmapClient.callOne("Email/get", arguments)
        if (result.getValue("notFound").jsonArray.isNotEmpty()) return EmailLookup.NotFound
        val email = result.getValue("list").jsonArray.first().jsonObject
        return EmailLookup.Found(email.toEmailDetail(ownDomain(), aliasEmails(listOf(email))))
    }

    /** One message's keywords alone; null when it is gone. */
    suspend fun keywords(id: String): Set<String>? = keySets(listOf(id), "keywords")[id]

    /**
     * Patches only the named keywords, so anything else on the message, including the labeler's,
     * is left as it is. Returns the server's reason when it refuses, null when it took the patch.
     */
    suspend fun updateKeywords(
        id: String,
        patch: Map<String, Boolean>,
    ): String? = updateKeywords(listOf(id), patch)[id]

    /** One request for every id in [ids]; the result maps each id the server refused to its reason. */
    suspend fun updateKeywords(
        ids: List<String>,
        patch: Map<String, Boolean>,
    ): Map<String, String> {
        val refused = setEmails(update = ids.associateWith { patch.pointers("keywords") })
        ids.filter { it !in refused }.forEach { _keywordPatches.emit(KeywordPatch(it, patch)) }
        return refused
    }

    /** Takes the messages out of the Inbox; one filed nowhere else goes to the Archive mailbox, made on first use. */
    suspend fun archive(ids: Collection<String>): MailMove =
        moving(ids) {
            val inbox = mailboxId(INBOX) ?: return@moving MailMove.NONE
            val before = mailboxIdsOf(ids).filterValues { inbox in it }
            val archive = if (before.values.any { it == setOf(inbox) }) archiveMailboxId() else null
            val after = before.mapValues { (_, boxes) -> (boxes - inbox).ifEmpty { setOfNotNull(archive) } }
            val stranded = after.filterValues { it.isEmpty() }.keys
            val done = move(before - stranded, after - stranded)
            done.copy(refused = done.refused + stranded.associateWith { "no Archive mailbox" })
        }

    /** Files the messages in Trash alone; with no Trash mailbox every one is refused. */
    suspend fun trash(ids: Collection<String>): MailMove = fileIn(ids, TRASH, "no Trash mailbox")

    /** Files the messages in Junk alone; with no Junk mailbox every one is refused. */
    suspend fun reportSpam(ids: Collection<String>): MailMove = fileIn(ids, JUNK, "no Junk mailbox")

    /** Files the messages in the Inbox alone, out of Junk. */
    suspend fun notSpam(ids: Collection<String>): MailMove = fileIn(ids, INBOX, "no Inbox")

    /** Files the messages in [mailboxId] alone, wherever they were. */
    suspend fun moveTo(
        ids: Collection<String>,
        mailboxId: String,
    ): MailMove = moving(ids) { fileAlone(ids, mailboxId) }

    /** Puts every message [done] moved back where it was. */
    suspend fun undo(done: MailMove): MailMove =
        moving(done.moved) { move(done.after.filterKeys { it in done.moved }, done.before.filterKeys { it in done.moved }) }

    /**
     * Deletes for good, with no undo, only those of [ids] filed in Trash alone or Junk alone: a thread
     * there can still hold a message filed elsewhere. Returns the server's reason for each id it kept.
     */
    suspend fun destroy(ids: Collection<String>): Map<String, String> =
        moving(ids) {
            val doomed = mailboxIdsOf(ids).filterValues { discardedOnly(it) }.keys
            if (doomed.isEmpty()) emptyMap() else setEmails(destroy = doomed)
        }

    /**
     * Empties the [role] mailbox: a message filed there alone is deleted for good, one also filed
     * elsewhere only leaves it. Ids are paged in from the server, so a large mailbox empties whole.
     */
    suspend fun empty(role: String): Emptied {
        val box = mailboxId(role) ?: return Emptied(0, emptyMap())
        val filed = filedIn(box)
        if (filed.isEmpty()) return Emptied(0, emptyMap())
        return moving(filed.keys) {
            val doomed = filed.filterValues { it == setOf(box) }.keys
            val refused =
                filed.entries.chunked(SET_CHUNK).flatMap { chunk ->
                    val (gone, kept) = chunk.partition { it.key in doomed }
                    val unfiled = kept.associate { (id, boxes) -> id to mailboxPatch(boxes, boxes - box).pointers("mailboxIds") }
                    setEmails(update = unfiled, destroy = gone.map { it.key }).entries
                }.associate { it.key to it.value }
            Emptied(doomed.count { it !in refused }, refused)
        }
    }

    /** Every message in [mailboxId] with the mailboxes it is filed in, a page of ids and their mailboxes per request. */
    private suspend fun filedIn(mailboxId: String): Map<String, Set<String>> {
        val filed = mutableMapOf<String, Set<String>>()
        var position = 0
        do {
            val query =
                buildJsonObject {
                    put("filter", condition("inMailbox", mailboxId))
                    put("position", position)
                    put("limit", SET_CHUNK)
                }
            val outcomes = jmapClient.queryAndGet(query, listOf("mailboxIds"))
            val found = outcomes.query.resultOrThrow().idList("ids").size
            filed += outcomes.get.resultOrThrow().keySets("mailboxIds")
            position += found
        } while (found == SET_CHUNK)
        return filed
    }

    private suspend fun fileIn(
        ids: Collection<String>,
        role: String,
        missing: String,
    ): MailMove =
        moving(ids) {
            val target = mailboxId(role) ?: return@moving MailMove(emptyMap(), emptyMap(), ids.associateWith { missing })
            fileAlone(ids, target)
        }

    private suspend fun fileAlone(
        ids: Collection<String>,
        target: String,
    ): MailMove {
        val before = mailboxIdsOf(ids).filterValues { it != setOf(target) }
        return move(before, before.mapValues { setOf(target) })
    }

    /** Every header field of one message, unfolded and in order; null when it is gone. */
    suspend fun headers(id: String): List<HeaderField>? {
        val found = getEmails(listOf(id), "headers").getValue("list").jsonArray.firstOrNull() ?: return null
        return found.jsonObject.getValue("headers").jsonArray.map {
            HeaderField(it.jsonObject.getValue("name").jsonPrimitive.content, unfold(it.jsonObject.getValue("value").jsonPrimitive.content))
        }
    }

    /** Announces [ids] leaving their mailboxes before [block] runs and again once it has finished, however it ended. */
    private suspend fun <T> moving(
        ids: Collection<String>,
        block: suspend () -> T,
    ): T {
        val moving = ids.toSet()
        _moves.emit(MoveEvent(moving, done = false))
        try {
            return block()
        } finally {
            _moves.emit(MoveEvent(moving, done = true))
        }
    }

    private suspend fun mailboxIdsOf(ids: Collection<String>): Map<String, Set<String>> = keySets(ids, "mailboxIds")

    /** The keys of one object-valued [property] of each message in [ids] that still exists. */
    private suspend fun keySets(
        ids: Collection<String>,
        property: String,
    ): Map<String, Set<String>> = getEmails(ids, property).keySets(property)

    private suspend fun getEmails(
        ids: Collection<String>,
        property: String,
    ): JsonObject {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("properties") { add(JsonPrimitive(property)) }
            }
        return jmapClient.callOne("Email/get", arguments)
    }

    private suspend fun move(
        before: Map<String, Set<String>>,
        after: Map<String, Set<String>>,
    ): MailMove {
        if (before.isEmpty()) return MailMove.NONE
        val refused = setEmails(update = before.mapValues { (id, boxes) -> mailboxPatch(boxes, after.getValue(id)).pointers("mailboxIds") })
        return MailMove(before, after, refused)
    }

    /**
     * One Email/set of JSON-pointer patches by message and of messages to delete for good;
     * returns the server's reason for each id it refused.
     */
    private suspend fun setEmails(
        update: Map<String, Map<String, Boolean>> = emptyMap(),
        destroy: Collection<String> = emptyList(),
    ): Map<String, String> {
        val arguments =
            buildJsonObject {
                if (update.isNotEmpty()) {
                    putJsonObject("update") {
                        update.forEach { (id, patch) ->
                            putJsonObject(id) { patch.forEach { (path, set) -> put(path, if (set) JsonPrimitive(true) else JsonNull) } }
                        }
                    }
                }
                if (destroy.isNotEmpty()) putJsonArray("destroy") { destroy.forEach { add(JsonPrimitive(it)) } }
            }
        val result = jmapClient.callOne("Email/set", arguments)
        result["newState"]?.jsonPrimitive?.contentOrNull?.let { state -> ownStates.update { (it + state).takeLast(OWN_STATES) } }
        val notUpdated = update.keys.mapNotNull { id -> result.setErrorReason("notUpdated", id)?.let { id to it } }
        val notDestroyed = destroy.mapNotNull { id -> result.setErrorReason("notDestroyed", id)?.let { id to it } }
        return (notUpdated + notDestroyed).toMap()
    }

    /** The Archive mailbox, made on first use; null when the server will not make one. */
    internal suspend fun archiveMailboxId(): String? =
        mailboxId(ARCHIVE) ?: rolesLock.withLock {
            roleIds?.get(ARCHIVE)?.let { return@withLock it }
            val arguments =
                buildJsonObject {
                    putJsonObject("create") {
                        putJsonObject(ARCHIVE) {
                            put("name", "Archive")
                            put("role", ARCHIVE)
                        }
                    }
                }
            val created = jmapClient.callOne("Mailbox/set", arguments)["created"]?.jsonObject?.get(ARCHIVE)?.jsonObject
            created?.getValue("id")?.jsonPrimitive?.content?.also { roleIds = roleIds.orEmpty() + (ARCHIVE to it) }
        }

    /** The identity for the account's own address: who the account avatar is, and which senders are its own server. */
    suspend fun identity(): Identity? = identities().mainIdentity(jmapClient.session.accountName)

    /** Everyone the newest messages name, ranked by [rankContacts], from the same fetch search completion reads. */
    suspend fun contacts(): List<Contact> = rankContacts(sample.emails(), identities().map { it.email }.toSet())

    /** Who wrote and what was labelled in the newest messages, read afresh, which also renews what search completion reads. */
    suspend fun directory(): Directory {
        val discarded = setOfNotNull(mailboxId(JUNK), mailboxId(TRASH))
        return directory(sample.emails(fresh = true), identities().map { it.email }.toSet(), discarded)
    }

    /** Every identity the account may send as, fetched once. */
    private suspend fun identities(): List<Identity> =
        identityLock.withLock {
            identities ?: fetchIdentities().also { identities = it }
        }

    internal suspend fun ownDomain(): String? = identity()?.email?.substringAfterLast('@')

    /**
     * Every identity's address but the main one, in lower case; none while the main one is unknown.
     * An alias minted or enabled since the identities were fetched has an identity only the server
     * knows, so a Delivered-To of [emails] that names no known address refetches them once.
     */
    private suspend fun aliasEmails(emails: List<JsonObject>): Set<String> {
        val cached = identities()
        val known = cached.map { it.email.lowercase() }.toSet()
        val unknown = emails.any { email -> email.texts(DELIVERED_TO).firstOrNull()?.lowercase()?.let { it !in known } == true }
        val all = if (unknown) identityLock.withLock { fetchIdentities().also { identities = it } } else cached
        val main = all.mainIdentity(jmapClient.session.accountName) ?: return emptySet()
        return all.map { it.email.lowercase() }.toSet() - main.email.lowercase()
    }

    private suspend fun fetchIdentities(): List<Identity> =
        jmapClient.callOne("Identity/get", JsonObject(emptyMap())).getValue("list").jsonArray.map { it.jsonObject.toIdentity() }
}

internal fun JsonObject.toMailbox() =
    Mailbox(
        id = getValue("id").jsonPrimitive.content,
        name = getValue("name").jsonPrimitive.content,
        role = get("role")?.jsonPrimitive?.contentOrNull,
        parentId = get("parentId")?.jsonPrimitive?.contentOrNull,
        totalEmails = get("totalEmails")?.jsonPrimitive?.int ?: 0,
        unreadEmails = get("unreadEmails")?.jsonPrimitive?.int ?: 0,
    )

// subject and from are nullable in RFC 8621: reading a null subject as a plain string
// returns the literal word "null", and reading a null from as an array throws.
internal fun JsonObject.toEmailSummary(ownDomain: String?): EmailSummary {
    val from = addressList("from").firstOrNull()
    return EmailSummary(
        id = getValue("id").jsonPrimitive.content,
        threadId = getValue("threadId").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = from,
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        keywords = getValue("keywords").jsonObject.keys,
        preview = get("preview")?.jsonPrimitive?.contentOrNull.orEmpty(),
        provenance = provenance(from, ownDomain),
        hasAttachment = get("hasAttachment")?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

private fun MethodOutcome.anchorNotFound() = (this as? MethodOutcome.Failure)?.error?.type == "anchorNotFound"

private fun JsonObject.queryState() = getValue("queryState").jsonPrimitive.content

/** email is null for MIME group syntax ("undisclosed-recipients:;"), which carries a name and no address. */
private fun JsonObject.toEmailAddress(): EmailAddress? {
    val email = get("email")?.jsonPrimitive?.contentOrNull ?: return null
    return EmailAddress(name = get("name")?.jsonPrimitive?.contentOrNull, email = email)
}

internal fun JsonObject.addressList(key: String): List<EmailAddress> =
    (get(key) as? JsonArray)?.mapNotNull { it.jsonObject.toEmailAddress() }.orEmpty()

internal fun JsonObject.idList(key: String): List<String> = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

/** The keys of one object-valued [property] of each message an Email/get listed, by message id. */
private fun JsonObject.keySets(property: String): Map<String, Set<String>> =
    getValue("list").jsonArray.associate {
        it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue(property).jsonObject.keys
    }

private fun List<Mailbox>.roleIds(): Map<String, String> = mapNotNull { box -> box.role?.let { it to box.id } }.toMap()

/**
 * textBody already picks the best single-part representation per RFC 8621; an
 * HTML-only message arrives here as one text/html part, flattened for the plain
 * body and kept as-is in [EmailDetail.html] for the reader to render.
 */
private fun JsonObject.toEmailDetail(
    ownDomain: String?,
    aliases: Set<String>,
): EmailDetail {
    val bodyValues = getValue("bodyValues").jsonObject
    val parts =
        getValue("textBody").jsonArray.map { part ->
            val partObject = part.jsonObject
            val partId = partObject.getValue("partId").jsonPrimitive.content
            val type = partObject.getValue("type").jsonPrimitive.content
            val value = bodyValues.getValue(partId).jsonObject
            val raw = value.getValue("value").jsonPrimitive.content
            val text = if (type == "text/html") flattenHtml(raw) else raw
            text to value.getValue("isTruncated").jsonPrimitive.boolean
        }
    val from = addressList("from").firstOrNull()
    val html = htmlBody(bodyValues)
    return EmailDetail(
        id = getValue("id").jsonPrimitive.content,
        subject = get("subject")?.jsonPrimitive?.contentOrNull.orEmpty(),
        from = from,
        to = addressList("to"),
        cc = addressList("cc"),
        replyTo = addressList("replyTo"),
        messageId = idList("messageId"),
        references = idList("references"),
        receivedAt = Instant.parse(getValue("receivedAt").jsonPrimitive.content),
        body = parts.joinToString("\n\n") { it.first },
        bodyTruncated = parts.any { it.second },
        html = html,
        preview = get("preview")?.jsonPrimitive?.contentOrNull.orEmpty(),
        keywords = getValue("keywords").jsonObject.keys,
        provenance = provenance(from, ownDomain),
        mailboxIds = (get("mailboxIds") as? JsonObject)?.keys.orEmpty(),
        attachments = attachmentList(html?.html),
        inlineImages = inlineImages(html?.html),
        arrivedVia = texts(DELIVERED_TO).firstOrNull()?.takeIf { it.lowercase() in aliases },
        deliveredTo = texts(DELIVERED_TO).firstOrNull(),
    )
}

/** The text/html parts alone: htmlBody also lists text/plain parts when a message has no HTML. */
private fun JsonObject.htmlBody(bodyValues: JsonObject): HtmlBody? {
    val values =
        getValue("htmlBody").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "text/html" }
            .map { bodyValues.getValue(it.getValue("partId").jsonPrimitive.content).jsonObject }
    if (values.isEmpty()) return null
    return HtmlBody(
        html = values.joinToString("\n") { it.getValue("value").jsonPrimitive.content },
        truncated = values.any { it.getValue("isTruncated").jsonPrimitive.boolean },
    )
}

/** Each key of a set-or-clear patch as a JSON pointer under [property]. */
private fun Map<String, Boolean>.pointers(property: String): Map<String, Boolean> =
    mapKeys { (key, _) -> "$property/${key.pointerEscaped()}" }

/** RFC 5322 unfolding: a line break followed by whitespace continues the field. */
internal fun unfold(value: String): String = value.replace(Regex("\r?\n(?=[ \t])"), "").trim()

/** RFC 6901: a keyword holding `~` or `/` must not split the patch path. */
private fun String.pointerEscaped(): String = replace("~", "~0").replace("/", "~1")
