package com.verdenroz.corvid.core.data.outbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val STATE_SCHEMA = 1
private const val MESSAGE_SUFFIX = ".msg"
private const val STATE_SUFFIX = ".state"
private const val QUARANTINE_SUFFIX = ".bad"

@Serializable
private class StateFile(
    val schema: Int,
    val key: String,
    val createdAt: Long,
    val attempt: Int,
    val state: OutboxState,
)

/**
 * One directory of `<id>.msg` (an immutable sealed message) and `<id>.state` (its progress, in
 * plaintext). A file that cannot be read is renamed to `.bad` and reported as an [OutboxFault],
 * never treated as an empty outbox; an unreadable state file takes its message with it. Only one
 * store holds a directory at a time.
 */
class OutboxStore private constructor(private val directory: File, private val lock: OutboxLock) : AutoCloseable {
    fun enqueue(
        id: String,
        message: SealedMessage,
        createdAt: Long,
    ): OutboxItem {
        writeAtomically(file(id, MESSAGE_SUFFIX), encodeEnvelope(message))
        return OutboxItem(id, createdAt, 0, message.keyFingerprint, OutboxState.Queued).also(::save)
    }

    fun save(item: OutboxItem) {
        val record = StateFile(STATE_SCHEMA, item.keyFingerprint, item.createdAt, item.attempt, item.state)
        writeAtomically(file(item.id, STATE_SUFFIX), Json.encodeToString(StateFile.serializer(), record).toByteArray())
    }

    /** Oldest first. */
    fun list(): OutboxListing {
        val read = directory.listFiles { file -> file.name.endsWith(STATE_SUFFIX) }.orEmpty().map { readState(it) }
        return OutboxListing(
            items = read.filterIsInstance<StateRead.Item>().map { it.item }.sortedWith(compareBy({ it.createdAt }, { it.id })),
            faults = read.filterIsInstance<StateRead.Faulty>().map { it.fault },
        )
    }

    /** Ids whose files were quarantined and that have no live state file, which stay reported until removed. */
    fun quarantined(): List<String> =
        directory.listFiles { file -> file.name.endsWith(QUARANTINE_SUFFIX) }.orEmpty()
            .map { it.name.substringBefore('.') }
            .distinct()
            .filterNot { file(it, STATE_SUFFIX).exists() }
            .sorted()

    fun removeQuarantined(id: String) {
        val files = directory.listFiles { file -> file.name.startsWith("$id.") && file.name.endsWith(QUARANTINE_SUFFIX) }
        files.orEmpty().forEach { it.delete() }
    }

    /** Changes whenever the message file does, and is null while it is missing. */
    fun messageStamp(id: String): String? = file(id, MESSAGE_SUFFIX).takeIf { it.exists() }?.let { "${it.lastModified()}:${it.length()}" }

    fun readMessage(id: String): MessageRead {
        val file = file(id, MESSAGE_SUFFIX)
        if (!file.exists()) return MessageRead.Missing
        return when (val read = decodeEnvelope(file.readBytes())) {
            is EnvelopeRead.Valid -> MessageRead.Found(read.message)
            is EnvelopeRead.Corrupt -> MessageRead.Faulty(OutboxFault.Corrupt(id, quarantine(file)))
            is EnvelopeRead.Foreign -> MessageRead.Faulty(OutboxFault.ForeignSchema(id, quarantine(file), read.found))
        }
    }

    /** Drops the message body and keeps a tombstone naming only the server's copy. */
    fun finish(item: OutboxItem) {
        save(item)
        file(item.id, MESSAGE_SUFFIX).delete()
    }

    fun remove(id: String) {
        file(id, MESSAGE_SUFFIX).delete()
        file(id, STATE_SUFFIX).delete()
        removeQuarantined(id)
    }

    override fun close() = lock.close()

    /** The item named [id], or null if it has no state file or the file was unreadable. */
    fun read(id: String): OutboxItem? = file(id, STATE_SUFFIX).takeIf { it.exists() }?.let { (readState(it) as? StateRead.Item)?.item }

    private fun readState(file: File): StateRead {
        val id = file.name.removeSuffix(STATE_SUFFIX)
        val parsed = parse(file.readText())
        val schema = parsed?.get("schema")?.jsonPrimitive?.intOrNull
        val record =
            if (parsed != null && schema == STATE_SCHEMA) {
                try {
                    Json.decodeFromJsonElement(StateFile.serializer(), parsed)
                } catch (_: SerializationException) {
                    null
                }
            } else {
                null
            }
        if (record == null) {
            val fault =
                if (schema == null || schema == STATE_SCHEMA) {
                    OutboxFault.Corrupt(id, quarantine(file))
                } else {
                    OutboxFault.ForeignSchema(id, quarantine(file), schema.toString())
                }
            file(id, MESSAGE_SUFFIX).takeIf { it.exists() }?.let(::quarantine)
            return StateRead.Faulty(fault)
        }
        return StateRead.Item(OutboxItem(id, record.createdAt, record.attempt, record.key, record.state))
    }

    private fun parse(text: String): JsonObject? =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun quarantine(file: File): String {
        val renamed = File(file.parentFile, file.name + QUARANTINE_SUFFIX)
        Files.move(file.toPath(), renamed.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return renamed.name
    }

    private fun file(
        id: String,
        suffix: String,
    ) = File(directory, id + suffix)

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val temp = File(directory, target.name + ".tmp")
        FileOutputStream(temp).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private sealed interface StateRead {
        class Item(val item: OutboxItem) : StateRead

        class Faulty(val fault: OutboxFault) : StateRead
    }

    companion object {
        private val shortLived = Mutex()

        /** Runs [block] on a store opened only for it, null while a long-lived store holds [directory]. */
        suspend fun <T> withStore(
            directory: File,
            block: (OutboxStore) -> T,
        ): T? = shortLived.withLock { open(directory)?.use(block) }

        /** Opens [directory] once every [withStore] in this process has let go of it. */
        suspend fun openWhenFree(directory: File): OutboxStore? = shortLived.withLock { open(directory) }

        /** Null while another store holds [directory]. */
        fun open(directory: File): OutboxStore? {
            directory.mkdirs()
            return OutboxLock.acquire(directory)?.let { OutboxStore(directory, it) }
        }
    }
}
