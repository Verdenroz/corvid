package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.setErrorReason
import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterRule
import com.verdenroz.corvid.core.model.Mailbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val SCRIPT_NAME = "corvid-filters"
private const val SIEVE_TYPE = "application/sieve"
private const val CREATION_ID = "corvid"

/** [foreignActiveScript] names the active script that is not Corvid's, if one is. */
data class FilterSet(val rules: List<FilterRule>, val foreignActiveScript: String?)

sealed interface SaveResult {
    data object Saved : SaveResult

    /** Nothing was written: activating Corvid's script would deactivate [scriptName]. */
    data class NeedsConfirmation(val scriptName: String) : SaveResult

    /** The server refused the script, or the rules cannot be written; [reason] is its own words. */
    data class Rejected(val reason: String) : SaveResult
}

/**
 * Every save regenerates and uploads the whole script. Another script is never
 * deleted, and never deactivated unless the caller passes `replaceForeign`.
 */
class FiltersRepository(
    private val jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
) {
    suspend fun load(): FilterSet {
        val scripts = scripts()
        val rules =
            scripts.firstOrNull { it.name == SCRIPT_NAME }?.let { own ->
                val script = jmapClient.downloadBlob(own.blobId, "$SCRIPT_NAME.sieve", SIEVE_TYPE).decodeToString()
                parseRules(script) ?: throw FiltersFormatException()
            }
        return FilterSet(rules.orEmpty(), scripts.foreignActive()?.name)
    }

    /** Each folder's name is its full path, `Finance/Receipts`, so same-named children stay distinct. */
    suspend fun folders(): List<FilterAction.MoveToFolder> {
        val all = mailboxRepository.mailboxes()
        val byId = all.associateBy { it.id }
        return all.map { FilterAction.MoveToFolder(it.id, it.path(byId)) }
    }

    suspend fun save(
        rules: List<FilterRule>,
        replaceForeign: Boolean,
    ): SaveResult {
        val scripts = scripts()
        val foreign = scripts.foreignActive()
        if (foreign != null && !replaceForeign) return SaveResult.NeedsConfirmation(foreign.name)

        val trash = mailboxRepository.findMailboxByRole("trash")?.let { FilterAction.MoveToFolder(it.id, it.name) }
        val needsTrash = rules.any { it.enabled && FilterAction.MoveToTrash in it.actions }
        if (needsTrash && trash == null) return SaveResult.Rejected("this account has no Trash folder")
        val blobId = jmapClient.uploadBlob(generateSieve(rules, trash).encodeToByteArray(), SIEVE_TYPE)
        val own = scripts.firstOrNull { it.name == SCRIPT_NAME }
        val arguments =
            buildJsonObject {
                if (own == null) {
                    putJsonObject("create") {
                        putJsonObject(CREATION_ID) {
                            put("name", SCRIPT_NAME)
                            put("blobId", blobId)
                        }
                    }
                } else {
                    putJsonObject("update") { putJsonObject(own.id) { put("blobId", blobId) } }
                }
                put("onSuccessActivateScript", own?.id ?: "#$CREATION_ID")
            }
        val result = jmapClient.callOne("SieveScript/set", arguments)
        val reason = result.setErrorReason("notCreated", CREATION_ID) ?: own?.let { result.setErrorReason("notUpdated", it.id) }
        return if (reason == null) SaveResult.Saved else SaveResult.Rejected(reason)
    }

    private suspend fun scripts(): List<ScriptInfo> =
        jmapClient.callOne("SieveScript/get", JsonObject(emptyMap())).getValue("list").jsonArray.map { it.jsonObject.toScriptInfo() }
}

private fun Mailbox.path(byId: Map<String, Mailbox>): String = parentId?.let { byId[it] }?.let { "${it.path(byId)}/$name" } ?: name

private data class ScriptInfo(
    val id: String,
    val name: String,
    val blobId: String,
    val isActive: Boolean,
)

private fun List<ScriptInfo>.foreignActive(): ScriptInfo? = firstOrNull { it.isActive && it.name != SCRIPT_NAME }

private fun JsonObject.toScriptInfo() =
    ScriptInfo(
        id = getValue("id").jsonPrimitive.content,
        name = getValue("name").jsonPrimitive.content,
        blobId = getValue("blobId").jsonPrimitive.content,
        isActive = getValue("isActive").jsonPrimitive.boolean,
    )
