package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.MethodOutcome
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

val DEVICE_NAME_PATTERN = Regex("[a-z0-9-]{1,32}")

private const val DEVICES_FOLDER_NAME = "corvid-devices"
private const val KEY_VERSION: Byte = 1
private const val KEY_LENGTH = 32
private const val KEY_CONTENT_TYPE = "application/octet-stream"

sealed interface DeviceKeyLookup {
    class Found(val key: ByteArray) : DeviceKeyLookup

    data object Missing : DeviceKeyLookup

    data object Damaged : DeviceKeyLookup

    /** More than one root `corvid-devices` folder exists; never resolved by picking one. */
    data object Ambiguous : DeviceKeyLookup

    class Unreachable(val error: JmapError) : DeviceKeyLookup
}

/**
 * Stores each device's key as a file under a `corvid-devices` FileNode
 * folder, so no server component is needed beyond the account's own
 * `urn:ietf:params:jmap:filenode` storage. Content is 1 version byte + 32
 * random bytes; a file of any other size or leading byte is refused and
 * left alone, since silently regenerating a damaged key would let a device
 * that lost sync keep the access it should have been locked out of.
 *
 * Stalwart's `FileNode/query` rejects `parentId: null` in a filter (a
 * server quirk, not the draft spec), so finding the root-level folder means
 * filtering by name alone and checking `parentId == null` on the result.
 */
class DeviceKeyRepository(private val jmapClient: JmapClient) {
    suspend fun fetch(deviceName: String): DeviceKeyLookup =
        try {
            val folderId = findRootNode(DEVICES_FOLDER_NAME) ?: return DeviceKeyLookup.Missing
            val fileId = findChildNode(folderId, fileName(deviceName)) ?: return DeviceKeyLookup.Missing
            val blobId = blobIdOf(fileId) ?: return DeviceKeyLookup.Missing
            parseKey(jmapClient.downloadBlob(blobId, fileName(deviceName), KEY_CONTENT_TYPE))
        } catch (e: JmapException) {
            DeviceKeyLookup.Unreachable(e.error)
        } catch (e: DeviceKeyConflict.AmbiguousRootFolder) {
            DeviceKeyLookup.Ambiguous
        }

    /**
     * Always destroys any existing file first: decision 10 never adopts one.
     * Fails typed rather than guessing if the destroy, the create, or a
     * concurrent enrollment leaves the folder ambiguous.
     */
    suspend fun enroll(deviceName: String): ByteArray {
        val folderId =
            findRootNode(DEVICES_FOLDER_NAME) ?: run {
                createNode(name = DEVICES_FOLDER_NAME, parentId = null)
                checkNotNull(findRootNode(DEVICES_FOLDER_NAME)) { "corvid-devices folder vanished right after creation" }
            }
        findChildNode(folderId, fileName(deviceName))?.let { destroyNode(it) }

        val key = Random.nextBytes(KEY_LENGTH)
        val blobId = jmapClient.uploadBlob(byteArrayOf(KEY_VERSION) + key, KEY_CONTENT_TYPE)
        createNode(name = fileName(deviceName), parentId = folderId, blobId = blobId)
        return key
    }

    private fun fileName(deviceName: String) = "$deviceName.key"

    private fun parseKey(bytes: ByteArray): DeviceKeyLookup =
        if (bytes.size == KEY_LENGTH + 1 && bytes[0] == KEY_VERSION) {
            val key = bytes.copyOfRange(1, bytes.size)
            bytes.fill(0)
            DeviceKeyLookup.Found(key)
        } else {
            DeviceKeyLookup.Damaged
        }

    private suspend fun findRootNode(name: String): String? {
        val candidates = queryByName(name)
        if (candidates.isEmpty()) return null
        val roots =
            getNodes(candidates, listOf("id", "parentId"))
                .filter { it["parentId"] is JsonNull }
        return when (roots.size) {
            0 -> null
            1 -> roots.single().getValue("id").jsonPrimitive.content
            else -> throw DeviceKeyConflict.AmbiguousRootFolder(roots.size)
        }
    }

    private suspend fun findChildNode(
        parentId: String,
        name: String,
    ): String? {
        val arguments =
            buildJsonObject {
                putJsonObject("filter") {
                    put("parentId", parentId)
                    put("name", name)
                }
            }
        return call("FileNode/query", arguments).getValue("ids").jsonArray.map { it.jsonPrimitive.content }.firstOrNull()
    }

    private suspend fun blobIdOf(fileId: String): String? {
        val nodes = getNodes(listOf(fileId), listOf("blobId"))
        return nodes.firstOrNull()?.get("blobId")?.jsonPrimitive?.content
    }

    private suspend fun queryByName(name: String): List<String> {
        val arguments = buildJsonObject { putJsonObject("filter") { put("name", name) } }
        return call("FileNode/query", arguments).getValue("ids").jsonArray.map { it.jsonPrimitive.content }
    }

    private suspend fun getNodes(
        ids: List<String>,
        properties: List<String>,
    ): List<JsonObject> {
        val arguments =
            buildJsonObject {
                putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("properties") { properties.forEach { add(JsonPrimitive(it)) } }
            }
        return call("FileNode/get", arguments).getValue("list").jsonArray.map { it.jsonObject }
    }

    private suspend fun createNode(
        name: String,
        parentId: String?,
        blobId: String? = null,
    ): String {
        val creation =
            buildJsonObject {
                put("name", name)
                put("parentId", parentId)
                blobId?.let { put("blobId", it) }
            }
        val arguments = buildJsonObject { putJsonObject("create") { put("n", creation) } }
        val result = call("FileNode/set", arguments)
        val createdId = result["created"]?.jsonObject?.get("n")?.jsonObject?.get("id")?.jsonPrimitive?.content
        return createdId ?: throw DeviceKeyConflict.CreateFailed(result.notCreatedReason("n"))
    }

    private suspend fun destroyNode(id: String) {
        val arguments = buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(id)) } }
        val result = call("FileNode/set", arguments)
        val destroyed = result["destroyed"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (id !in destroyed) throw DeviceKeyConflict.DestroyFailed(id, result.notDestroyedReason(id))
    }

    private fun JsonObject.notCreatedReason(creationId: String) =
        get("notCreated")?.jsonObject?.get(creationId)?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull

    private fun JsonObject.notDestroyedReason(id: String) =
        get("notDestroyed")?.jsonObject?.get(id)?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull

    private suspend fun call(
        method: String,
        arguments: JsonObject,
    ): JsonObject {
        val outcome = jmapClient.call(listOf(Invocation(method, arguments, "c0"))).getValue("c0")
        return when (outcome) {
            is MethodOutcome.Success -> outcome.result
            is MethodOutcome.Failure -> throw JmapException(outcome.error)
        }
    }
}
