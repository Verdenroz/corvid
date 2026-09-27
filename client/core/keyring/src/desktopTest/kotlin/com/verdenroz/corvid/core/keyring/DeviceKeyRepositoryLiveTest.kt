package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

private const val RIG_BASE_URL = "http://127.0.0.1:48080"
private const val RIG_USER = "admin@corvid.test"
private const val CORE_AND_FILENODE = "urn:ietf:params:jmap:core"
private const val FILENODE = "urn:ietf:params:jmap:filenode"

/**
 * Settles FileNode's real property names against a throwaway Stalwart
 * v0.16.21 rig (container `corvid-rig-client`, a loopback port in
 * 48000-48999), never n1. `CORVID_RIG_PASSWORD` is the admin password
 * `stalwart-cli update Bootstrap` printed for that container.
 *
 * Ignored by default: DeviceKeyRepository itself is bearer-only (decision
 * 5), and this rig has no OIDC issuer to mint one from, so this test
 * speaks basic auth directly rather than through JmapClient. It exists to
 * have run once against the real capability, not to run in CI.
 */
@Ignore
class DeviceKeyRepositoryLiveTest {
    private lateinit var client: HttpClient

    @BeforeTest
    fun connect() {
        val password = requireNotNull(System.getenv("CORVID_RIG_PASSWORD")) { "set CORVID_RIG_PASSWORD" }
        client =
            HttpClient(OkHttp) {
                expectSuccess = false
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
                install(Auth) {
                    basic {
                        credentials { BasicAuthCredentials(RIG_USER, password) }
                        sendWithoutRequest { true }
                    }
                }
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
    }

    @AfterTest
    fun disconnect() {
        client.close()
    }

    @Test
    fun `FileNode create, get and blob round trip match what DeviceKeyRepository expects`() =
        runTest {
            val session = client.get("$RIG_BASE_URL/jmap/session").body<JsonObject>()
            val accountId = session.getValue("primaryAccounts").jsonObject.getValue(FILENODE).jsonPrimitive.content

            val content = byteArrayOf(1) + ByteArray(32) { it.toByte() }
            val upload =
                client
                    .post("$RIG_BASE_URL/jmap/upload/$accountId/") {
                        contentType(ContentType.Application.OctetStream)
                        setBody(content)
                    }.body<JsonObject>()
            val blobId = upload.getValue("blobId").jsonPrimitive.content

            val createFolder =
                call(
                    accountId,
                    "FileNode/set",
                    buildJsonObject {
                        putJsonObject("create") {
                            putJsonObject("folder") {
                                put("name", "corvid-devices-live-test")
                                put("parentId", null as String?)
                            }
                        }
                    },
                )
            val folderId = createFolder.createdId("folder")

            val createFile =
                call(
                    accountId,
                    "FileNode/set",
                    buildJsonObject {
                        putJsonObject("create") {
                            putJsonObject("file") {
                                put("name", "live-test.key")
                                put("parentId", folderId)
                                put("blobId", blobId)
                                put("type", "application/octet-stream")
                            }
                        }
                    },
                )
            val fileId = createFile.createdId("file")

            val get = call(accountId, "FileNode/get", buildJsonObject { putJsonArray("ids") { add(JsonPrimitive(fileId)) } })
            val node = (get.getValue("list") as JsonArray).first().jsonObject
            assertEquals("live-test.key", node.getValue("name").jsonPrimitive.content)
            assertEquals(folderId, node.getValue("parentId").jsonPrimitive.content)
            assertEquals("file", node.getValue("nodeType").jsonPrimitive.content)

            // Stalwart re-wraps the uploaded blobId once it's referenced by a FileNode, so
            // downloading must use the id FileNode/get returns, never the upload response's.
            val storedBlobId = node.getValue("blobId").jsonPrimitive.content
            val downloadUrl = "$RIG_BASE_URL/jmap/download/$accountId/$storedBlobId/live-test.key?accept=application/octet-stream"
            val downloaded = client.get(downloadUrl).body<ByteArray>()
            assertEquals(content.toList(), downloaded.toList())

            call(accountId, "FileNode/set", buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(fileId)) } })
            call(accountId, "FileNode/set", buildJsonObject { putJsonArray("destroy") { add(JsonPrimitive(folderId)) } })
        }

    private suspend fun call(
        accountId: String,
        method: String,
        arguments: JsonObject,
    ): JsonObject {
        val withAccount = JsonObject(arguments + ("accountId" to JsonPrimitive(accountId)))
        val invocation = Invocation(method, withAccount, "c0")
        val requestBody =
            buildJsonObject {
                putJsonArray("using") {
                    add(JsonPrimitive(CORE_AND_FILENODE))
                    add(JsonPrimitive(FILENODE))
                }
                putJsonArray("methodCalls") { add(JmapJson.encodeToJsonElement(Invocation.serializer(), invocation)) }
            }
        val response =
            client
                .post("$RIG_BASE_URL/jmap/") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.body<JsonObject>()
        val responseInvocation = (response.getValue("methodResponses") as JsonArray).first()
        val decoded = JmapJson.decodeFromJsonElement(Invocation.serializer(), responseInvocation)
        check(decoded.name != "error") { "FileNode call failed: ${decoded.arguments}" }
        return decoded.arguments
    }

    private fun JsonObject.createdId(creationId: String): String =
        getValue("created").jsonObject.getValue(creationId).jsonObject.getValue("id").jsonPrimitive.content
}
