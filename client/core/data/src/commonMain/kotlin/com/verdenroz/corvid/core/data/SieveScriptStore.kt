package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.callOne
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val SIEVE_TYPE = "application/sieve"

internal data class StoredScript(val script: CorvidScript, val foreignActiveScript: String?)

/**
 * The one Sieve script Corvid owns. Another script is never deleted, and never
 * deactivated unless the caller passes `replaceForeign`.
 */
internal class SieveScriptStore(
    private val jmapClient: JmapClient,
    private val mailboxRepository: MailboxRepository,
) {
    suspend fun load(): StoredScript {
        val scripts = scripts()
        val own = scripts.firstOrNull { it.name == SCRIPT_NAME }
        val script = own?.let { parseCorvidScript(jmapClient.downloadBlob(it.blobId, "$SCRIPT_NAME.sieve", SIEVE_TYPE).decodeToString()) }
        return StoredScript(script ?: CorvidScript(), scripts.foreignActive()?.name)
    }

    suspend fun save(
        script: CorvidScript,
        replaceForeign: Boolean,
    ): SaveResult {
        val scripts = scripts()
        val foreign = scripts.foreignActive()
        if (foreign != null && !replaceForeign) return SaveResult.NeedsConfirmation(foreign.name)

        val trashId = mailboxRepository.findMailboxByRole("trash")?.id
        if (script.needsTrash && trashId == null) return SaveResult.Rejected("this account has no Trash folder")
        val blobId = jmapClient.uploadBlob(script.render(trashId).encodeToByteArray(), SIEVE_TYPE)
        val own = scripts.firstOrNull { it.name == SCRIPT_NAME }
        val reason = jmapClient.callOne("SieveScript/set", setScript(own, blobId)).setFailure(own)
        return if (reason == null) SaveResult.Saved else SaveResult.Rejected(reason)
    }

    private suspend fun scripts(): List<ScriptInfo> =
        jmapClient.callOne("SieveScript/get", JsonObject(emptyMap())).getValue("list").jsonArray.map { it.jsonObject.toScriptInfo() }
}

private fun List<ScriptInfo>.foreignActive(): ScriptInfo? = firstOrNull { it.isActive && it.name != SCRIPT_NAME }
