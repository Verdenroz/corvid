package com.verdenroz.corvid.core.data.outbox

import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.EmailAddress
import com.verdenroz.corvid.core.testing.FakeEmail
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.withSendableAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }
private val OTHER_KEY = ByteArray(33) { (it * 5 + 3).toByte() }
private val CLOCK =
    object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(5_000)
    }

private fun draft(
    subject: String = "hello",
    from: String? = null,
) = Draft(to = listOf(EmailAddress(null, "bob@example.test")), subject = subject, body = "hi", from = from)

private class Rig {
    val store = assertNotNull(OutboxStore.open(createTempDirectory().toFile()))
    val fake = FakeJmapServer().withSendableAccount()
    val queue = OutboxQueue(store, OutboxSealer.keyFor(DEVICE_KEY), "example.test", CLOCK)

    suspend fun sender(
        deviceKey: ByteArray = DEVICE_KEY,
        clock: Clock = CLOCK,
    ): OutboxSender {
        val client = fake.client()
        val compose = ComposeRepository(client, MailboxRepository(client))
        compose.routeFor(null)
        return OutboxSender(store, client, compose, deviceKey, clock)
    }

    fun item(id: String) = store.list().items.single { it.id == id }

    fun put(
        id: String,
        state: OutboxState,
    ) = store.save(item(id).copy(state = state))

    fun copy(
        id: String,
        mailbox: String,
        keywords: Set<String>,
    ) {
        fake.emails += FakeEmail(id, id, setOf(mailbox), "hello", null, "2024-01-01T00:00:00Z", keywords)
    }

    fun enqueueUnreadable(id: String) {
        store.enqueue(id, OutboxSealer.sealPayload(id, "{".toByteArray(), OutboxSealer.keyFor(DEVICE_KEY)), createdAt = 1)
    }

    fun stripOutboxKeywords() =
        fake.emails.replaceAll { email -> email.copy(keywords = email.keywords.filterNot { it.startsWith("corvid-outbox-") }.toSet()) }
}

class OutboxSenderTest {
    @Test
    fun `a queued message is sent once and leaves a tombstone naming the server copy`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), replacesDraftId = null)

            val drained = rig.sender().drain()

            assertEquals(listOf(Drained(id, ItemResult.Sent)), drained)
            val email = rig.fake.emails.single()
            assertEquals(setOf("sent"), email.mailboxIds)
            assertEquals(setOf("\$seen"), email.keywords)
            assertEquals(listOf("$id@example.test"), email.messageId)
            assertEquals(1, rig.fake.submissions.size)
            assertEquals(OutboxState.Sent(email.id, 5_000), rig.item(id).state)
            assertEquals(MessageRead.Missing, rig.store.readMessage(id))
        }

    @Test
    fun `sending a replacement destroys the draft it replaces`() =
        runTest {
            val rig = Rig()
            rig.fake.emails += FakeEmail("old", "old", setOf("drafts"), "hello", null, "2024-01-01T00:00:00Z", setOf("\$draft"))
            rig.queue.enqueue(draft(), replacesDraftId = "old")

            rig.sender().drain()

            assertEquals(listOf("sent1"), rig.fake.emails.map { it.id })
        }

    @Test
    fun `messages go out oldest first`() =
        runTest {
            val rig = Rig()
            val first = OutboxQueue(rig.store, OutboxSealer.keyFor(DEVICE_KEY), "example.test", ClockAt(1))
            val second = OutboxQueue(rig.store, OutboxSealer.keyFor(DEVICE_KEY), "example.test", ClockAt(2))
            second.enqueue(draft("second"), null)
            first.enqueue(draft("first"), null)

            rig.sender().drain()

            assertEquals(listOf("first", "second"), rig.fake.emails.map { it.subject })
        }

    @Test
    fun `a lost response after submission is resolved by the keyword probe and exactly one message is sent`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.loseResponses = 1

            assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = true))), sender.drain())
            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(1, rig.fake.submissions.size)
            assertEquals(1, rig.fake.emails.size)
            assertIs<OutboxState.Sent>(rig.item(id).state)
        }

    @Test
    fun `a request that created the draft but lost its response submits the existing draft and creates no second one`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Submitting(1))
            rig.fake.emails +=
                FakeEmail(
                    "staged",
                    "staged",
                    setOf("drafts"),
                    "hello",
                    null,
                    "2024-01-01T00:00:00Z",
                    keywords = setOf("\$draft", "corvid-outbox-$id"),
                    body = "hi",
                )

            assertEquals(listOf(Drained(id, ItemResult.Sent)), rig.sender().drain())

            assertEquals(listOf("staged"), rig.fake.emails.map { it.id })
            assertEquals(setOf("sent"), rig.fake.emails.single().mailboxIds)
            assertEquals(listOf("staged"), rig.fake.submissions.map { it.emailId })
        }

    @Test
    fun `a server refusal marks the item rejected and leaves the draft in drafts`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.fake.refuseSubmission = true
            val sender = rig.sender()

            val result = sender.drain().single().result

            assertEquals(RejectionCategory.Refused, assertIs<ItemResult.Rejected>(result).category)
            assertEquals(OutboxState.Rejected(RejectionCategory.Refused), rig.item(id).state)
            assertEquals(setOf("drafts"), rig.fake.emails.single().mailboxIds)
            assertEquals(emptyList(), sender.drain())
            assertEquals(0, rig.fake.submissions.size)
        }

    @Test
    fun `an ambiguous outcome with an unusable probe stays unconfirmed and is never resent`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.loseResponses = 1
            sender.drain()
            rig.fake.unreachable = true

            assertEquals(listOf(Drained(id, ItemResult.Deferred)), sender.drain())

            assertEquals(OutboxState.Unconfirmed, rig.item(id).state)
            assertEquals(1, rig.fake.submissions.size)
            assertEquals(1, rig.fake.emails.size)
        }

    @Test
    fun `a process kill between the submitting write and the request is retried through the probe`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Submitting(1))

            assertEquals(listOf(Drained(id, ItemResult.Sent)), rig.sender().drain())

            assertEquals(1, rig.fake.submissions.size)
            assertEquals(1, rig.fake.emails.size)
        }

    @Test
    fun `a message already unconfirmed whose keyword an older labeler stripped stays unconfirmed and is not resent`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.loseResponses = 1
            sender.drain()
            rig.stripOutboxKeywords()

            assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = false))), sender.drain())

            assertEquals(OutboxState.Unconfirmed, rig.item(id).state)
            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `an unconfirmed message the user chooses to send again goes out when the server holds no copy`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Unconfirmed)
            val sender = rig.sender()

            assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = false))), sender.drain())
            sender.resend(id)
            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `a rejected message the user chooses to send again goes out once the server accepts it`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.refuseSubmission = true

            assertIs<ItemResult.Rejected>(sender.drain().single().result)
            rig.fake.refuseSubmission = false
            sender.resend(id)
            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `a 429 on the send leaves the item queued and a later drain sends it once`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.tooManyApiRequests = 1

            assertEquals(listOf(Drained(id, ItemResult.Deferred)), sender.drain())
            assertEquals(OutboxState.Queued, rig.item(id).state)
            assertEquals(0, rig.item(id).attempt)
            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `a disabled alias is rejected and never sent from the main address`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(from = "gone.alias@example.test"), null)

            val result = rig.sender().drain().single().result

            assertEquals(RejectionCategory.AliasGone, assertIs<ItemResult.Rejected>(result).category)
            assertEquals(OutboxState.Rejected(RejectionCategory.AliasGone), rig.item(id).state)
            assertEquals(emptyList(), rig.fake.emails)
            assertEquals(emptyList(), rig.fake.submissions)
        }

    @Test
    fun `an undecodable message does not block the ones behind it`() =
        runTest {
            val rig = Rig()
            val bad = "bad"
            rig.enqueueUnreadable(bad)
            val good = rig.queue.enqueue(draft(), null)

            val drained = rig.sender().drain()

            assertEquals(listOf(Drained(bad, ItemResult.Unreadable), Drained(good, ItemResult.Sent)), drained)
        }

    @Test
    fun `a handshake failure before the send was written is deferred and the item stays queued`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.tlsFailure = true

            assertEquals(listOf(Drained(id, ItemResult.Deferred)), sender.drain())

            assertEquals(OutboxState.Queued, rig.item(id).state)
            assertEquals(0, rig.item(id).attempt)
        }

    @Test
    fun `a tls failure inside the session after the send is unconfirmed and the probe resolves it`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.loseResponses = 1
            rig.fake.loseResponsesAsTls = true

            assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = true))), sender.drain())
            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `an unconfirmed message whose probe is refused stays unconfirmed and is later resolved by the probe`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.loseResponses = 1
            sender.drain()
            rig.fake.malformedApiResponse = true

            assertEquals(listOf(Drained(id, ItemResult.Deferred)), sender.drain())
            assertEquals(OutboxState.Unconfirmed, rig.item(id).state)
            rig.fake.malformedApiResponse = false

            assertEquals(listOf(Drained(id, ItemResult.Sent)), rig.sender().drain())
            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `a message interrupted while recovering from an unconfirmed send is never created on an empty probe`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Unconfirmed)
            val sender = rig.sender()
            val gate = CompletableDeferred<Unit>()
            rig.fake.gate = gate

            val drained = async(Dispatchers.Default) { sender.drain() }
            withContext(Dispatchers.Default) {
                while (rig.item(id).state !is OutboxState.Submitting) delay(5)
            }
            assertEquals(OutboxState.Submitting(5_000, recovering = true), rig.item(id).state)
            gate.complete(Unit)
            drained.await()
            rig.put(id, OutboxState.Submitting(5_000, recovering = true))

            assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = false))), rig.sender().drain())

            assertEquals(emptyList(), rig.fake.emails)
        }

    @Test
    fun `a sent copy filed in another mailbox is found and marked sent`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.loseResponses = 1
            sender.drain()
            rig.fake.emails.replaceAll { it.copy(mailboxIds = setOf("archive")) }

            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(OutboxState.Sent("sent1", 5_000), rig.item(id).state)
            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `two sent copies of one message are marked sent without a second send`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Unconfirmed)
            repeat(2) { rig.copy("c$it", "sent", setOf("corvid-outbox-$id")) }

            assertEquals(listOf(Drained(id, ItemResult.Sent)), rig.sender().drain())

            assertEquals(emptyList(), rig.fake.submissions)
        }

    @Test
    fun `a staged copy beside a sent copy is ambiguous and the user can send again or discard`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Unconfirmed)
            val mark = setOf("corvid-outbox-$id")
            rig.copy("s", "sent", mark)
            rig.copy("d", "drafts", mark + "\$draft")
            val sender = rig.sender()

            assertEquals(listOf(Drained(id, ItemResult.Unconfirmed(transportFailed = false))), sender.drain())
            assertEquals(true, sender.discard(id))

            assertEquals(emptyList(), rig.store.list().items)
            assertEquals(MessageRead.Missing, rig.store.readMessage(id))
        }

    @Test
    fun `discarding a rejected message also deletes the draft the server staged for it`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.refuseSubmission = true
            assertIs<ItemResult.Rejected>(sender.drain().single().result)
            assertEquals(1, rig.fake.emails.size)

            assertEquals(true, sender.discard(id))

            assertEquals(emptyList(), rig.fake.emails)
        }

    @Test
    fun `discarding a rejected message leaves a copy of its keyword that is no longer a draft`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            rig.put(id, OutboxState.Rejected(RejectionCategory.Refused))
            rig.copy("s", "sent", setOf("corvid-outbox-$id"))
            val sender = rig.sender()

            assertEquals(true, sender.discard(id))

            assertEquals(listOf("s"), rig.fake.emails.map { it.id })
        }

    @Test
    fun `discarding an unconfirmed or unreadable message never calls the server`() =
        runTest {
            val rig = Rig()
            val unconfirmed = rig.queue.enqueue(draft(), null)
            rig.put(unconfirmed, OutboxState.Unconfirmed)
            rig.copy("d", "drafts", setOf("corvid-outbox-$unconfirmed", "\$draft"))
            rig.enqueueUnreadable("bad")
            val sender = rig.sender()
            val before = rig.fake.requestCount

            assertEquals(true, sender.discard(unconfirmed))
            assertEquals(true, sender.discard("bad"))

            assertEquals(before, rig.fake.requestCount)
            assertEquals(listOf("d"), rig.fake.emails.map { it.id })
        }

    @Test
    fun `a rejected message discarded while the server is unreachable leaves a cleanup that a later drain finishes`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.refuseSubmission = true
            assertIs<ItemResult.Rejected>(sender.drain().single().result)
            rig.fake.unreachable = true

            assertEquals(true, sender.discard(id))

            assertEquals(MessageRead.Missing, rig.store.readMessage(id))
            assertEquals(1, rig.fake.emails.size)
            assertIs<OutboxState.Sent>(rig.item(id).state)
            rig.fake.unreachable = false
            assertEquals(emptyList(), sender.drain())

            assertEquals(emptyList(), rig.fake.emails)
            assertEquals(emptyList(), rig.store.list().items)
        }

    @Test
    fun `discarding a rejected message does not hold the sender while its cleanup waits on the server`() =
        runTest {
            val rig = Rig()
            val rejected = rig.queue.enqueue(draft(), null)
            val other = rig.queue.enqueue(draft(), null)
            rig.put(rejected, OutboxState.Rejected(RejectionCategory.Refused))
            rig.put(other, OutboxState.Unconfirmed)
            val sender = rig.sender()
            rig.fake.gate = CompletableDeferred()

            withContext(Dispatchers.Default) {
                val discarding = async { sender.discard(rejected) }
                delay(300)
                withTimeout(5.seconds) { sender.resend(other) }
                rig.fake.gate?.complete(Unit)
                discarding.await()
            }

            assertEquals(OutboxState.Queued, rig.item(other).state)
        }

    @Test
    fun `a drain removes sent tombstones older than a week once their cleanup is done`() =
        runTest {
            val rig = Rig()
            val day = 24L * 60 * 60 * 1000
            val now = Instant.fromEpochMilliseconds(30 * day)
            val old = rig.queue.enqueue(draft(), null)
            val recent = rig.queue.enqueue(draft(), null)
            val untidy = rig.queue.enqueue(draft(), null)
            rig.put(old, OutboxState.Sent("e1", now.toEpochMilliseconds() - 8 * day))
            rig.put(recent, OutboxState.Sent("e2", now.toEpochMilliseconds() - 6 * day))
            rig.put(untidy, OutboxState.Sent("e3", now.toEpochMilliseconds() - 8 * day, Tidy(keyword = "corvid-outbox-$untidy")))
            val sender =
                rig.sender(
                    clock =
                        object : Clock {
                            override fun now() = now
                        },
                )
            rig.fake.unreachable = true

            sender.drain()

            assertEquals(setOf(recent, untidy), rig.store.list().items.map { it.id }.toSet())
        }

    @Test
    fun `a rejected or unreadable message can be discarded and a sent tombstone cannot`() =
        runTest {
            val rig = Rig()
            val rejected = rig.queue.enqueue(draft(), null)
            rig.put(rejected, OutboxState.Rejected(RejectionCategory.Refused))
            val unreadable = "bad"
            rig.enqueueUnreadable(unreadable)
            val sent = rig.queue.enqueue(draft(), null)
            rig.put(sent, OutboxState.Sent("e1", 1))
            val queued = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()

            assertEquals(listOf(true, true, false, false), listOf(rejected, unreadable, sent, queued).map { sender.discard(it) })
            assertEquals(setOf(sent, queued), rig.store.list().items.map { it.id }.toSet())
        }

    @Test
    fun `a transient method error on the submission leaves the staged draft for the retry to submit`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            rig.fake.submissionServerFail = true

            assertEquals(listOf(Drained(id, ItemResult.Deferred)), sender.drain())
            assertEquals(OutboxState.Queued, rig.item(id).state)
            assertEquals(setOf("drafts"), rig.fake.emails.single().mailboxIds)
            rig.fake.submissionServerFail = false

            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())

            assertEquals(1, rig.fake.emails.size)
            assertEquals(1, rig.fake.submissions.size)
        }

    @Test
    fun `a failed cleanup after a send is retried on the next drain`() =
        runTest {
            val rig = Rig()
            rig.fake.emails += FakeEmail("old", "old", setOf("drafts"), "hello", null, "2024-01-01T00:00:00Z", setOf("\$draft"))
            val id = rig.queue.enqueue(draft(), replacesDraftId = "old")
            val sender = rig.sender()
            rig.fake.apiRequestsBeforeRefusal = 1

            assertEquals(listOf(Drained(id, ItemResult.Sent)), sender.drain())
            val pending = rig.item(id).state as OutboxState.Sent
            assertEquals(Tidy("corvid-outbox-$id", "old"), pending.tidy)
            assertEquals(setOf("old", "sent1"), rig.fake.emails.map { it.id }.toSet())
            rig.fake.apiRequestsBeforeRefusal = null

            assertEquals(emptyList(), sender.drain())

            assertEquals(OutboxState.Sent("sent1", 5_000), rig.item(id).state)
            assertEquals(listOf("sent1"), rig.fake.emails.map { it.id })
            assertEquals(setOf("\$seen"), rig.fake.emails.single().keywords)
        }

    @Test
    fun `a sign-out leaves the item exactly as it was`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val before = rig.item(id)
            val sender = rig.sender()
            rig.fake.apiUnauthorized = true

            assertEquals(listOf(Drained(id, ItemResult.SignedOut)), sender.drain())

            assertEquals(before, rig.item(id))
        }

    @Test
    fun `a message sealed to a previous key is reported as such and not touched`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val before = rig.item(id)

            val result = rig.sender(deviceKey = OTHER_KEY).drain().single().result

            assertEquals(OutboxSealer.keyFor(DEVICE_KEY).fingerprint, assertIs<ItemResult.KeyMismatch>(result).sealedTo)
            assertEquals(before, rig.item(id))
            assertEquals(emptyList(), rig.fake.emails)
        }

    @Test
    fun `the submitting state is on disk before the first request is answered`() =
        runTest {
            val rig = Rig()
            val id = rig.queue.enqueue(draft(), null)
            val sender = rig.sender()
            val gate = CompletableDeferred<Unit>()
            rig.fake.gate = gate

            val drained = async(Dispatchers.Default) { sender.drain() }
            withContext(Dispatchers.Default) {
                while (rig.item(id).state !is OutboxState.Submitting) delay(5)
            }
            gate.complete(Unit)

            assertEquals(listOf(Drained(id, ItemResult.Sent)), drained.await())
        }
}

private class ClockAt(private val millis: Long) : Clock {
    override fun now() = Instant.fromEpochMilliseconds(millis)
}
