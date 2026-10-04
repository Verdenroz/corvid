package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.callOne
import com.verdenroz.corvid.core.jmap.resultOrThrow
import com.verdenroz.corvid.core.model.Mailbox
import com.verdenroz.corvid.core.model.MailboxRef
import com.verdenroz.corvid.core.model.refs
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.no_trash_folder
import com.verdenroz.corvid.core.text.uiText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val SIEVE_TYPE = "application/sieve"
private val MAILBOX_PROPERTIES = listOf("id", "name", "parentId", "role")

internal data class StoredScript(
    val script: CorvidScript,
    val foreignActiveScript: String?,
    val folders: List<MailboxRef>,
)

private class Snapshot(
    val scripts: List<ScriptInfo>,
    val mailboxes: List<Mailbox>,
) {
    val own: ScriptInfo? get() = scripts.firstOrNull { it.name == SCRIPT_NAME }
    val foreignActive: ScriptInfo? get() = scripts.firstOrNull { it.isActive && it.name != SCRIPT_NAME }
}

/**
 * The one Sieve script Corvid owns. Another script is never deleted, and never
 * deactivated unless the caller passes `replaceForeign`.
 */
internal class SieveScriptStore(private val jmapClient: JmapClient) {
    suspend fun load(): StoredScript {
        val snapshot = snapshot()
        val script =
            snapshot.own?.let {
                parseCorvidScript(
                    jmapClient.downloadBlob(it.blobId, "$SCRIPT_NAME.sieve", SIEVE_TYPE).decodeToString(),
                )
            }
        return StoredScript(script ?: CorvidScript(), snapshot.foreignActive?.name, snapshot.mailboxes.refs())
    }

    suspend fun save(
        script: CorvidScript,
        replaceForeign: Boolean,
    ): SaveResult {
        val snapshot = snapshot()
        snapshot.foreignActive?.let { if (!replaceForeign) return SaveResult.NeedsConfirmation(it.name) }

        val trashId = snapshot.mailboxes.firstOrNull { it.role == "trash" }?.id
        if (script.needsTrash && trashId == null) return SaveResult.Rejected(uiText(Res.string.no_trash_folder))
        val blobId = jmapClient.uploadBlob(script.render(trashId).encodeToByteArray(), SIEVE_TYPE)
        val reason = jmapClient.callOne("SieveScript/set", setScript(snapshot.own, blobId)).setFailure(snapshot.own)
        return if (reason == null) SaveResult.Saved else SaveResult.Rejected(UiText.FromServer(reason))
    }

    private suspend fun snapshot(): Snapshot {
        val outcomes =
            jmapClient.call(
                listOf(
                    Invocation("SieveScript/get", JsonObject(emptyMap()), "c0"),
                    Invocation(
                        "Mailbox/get",
                        buildJsonObject { put("properties", JsonArray(MAILBOX_PROPERTIES.map(::JsonPrimitive))) },
                        "c1",
                    ),
                ),
            )
        return Snapshot(
            scripts = outcomes.getValue("c0").resultOrThrow().getValue("list").jsonArray.map { it.jsonObject.toScriptInfo() },
            mailboxes = outcomes.getValue("c1").resultOrThrow().getValue("list").jsonArray.map { it.jsonObject.toMailbox() },
        )
    }
}
