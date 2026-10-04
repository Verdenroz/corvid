package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.setErrorReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val SCRIPT_NAME = "corvid"
private const val CREATION_ID = "corvid"

internal data class ScriptInfo(
    val id: String,
    val name: String,
    val blobId: String,
    val isActive: Boolean,
)

internal fun JsonObject.toScriptInfo() =
    ScriptInfo(
        id = getValue("id").jsonPrimitive.content,
        name = getValue("name").jsonPrimitive.content,
        blobId = getValue("blobId").jsonPrimitive.content,
        isActive = getValue("isActive").jsonPrimitive.boolean,
    )

/** Creates the script when [own] is null, else points it at the new blob; either way it is activated on success. */
internal fun setScript(
    own: ScriptInfo?,
    blobId: String,
): JsonObject =
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

internal fun JsonObject.setFailure(own: ScriptInfo?): String? =
    setErrorReason("notCreated", CREATION_ID) ?: own?.let { setErrorReason("notUpdated", it.id) }
