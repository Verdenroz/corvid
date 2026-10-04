package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.MethodOutcome
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val BASE_URL = "http://127.0.0.1:9999"

class FakeJmapServerSieveTest {
    @Test
    fun `a created script is activated by onSuccessActivateScript and stores its blob`() =
        runTest {
            val fake = FakeJmapServer()
            fake.sieveScripts += FakeSieveScript(id = "old", name = "other", blobId = "b0", isActive = true)
            val client = connect(fake)
            val blobId = client.uploadBlob("keep;".encodeToByteArray(), "application/sieve")

            val result = client.sieveSet(createScript("c1", blobId), activate = "#c1")

            val id = result.getValue("created").jsonObject.getValue("c1").jsonObject.getValue("id").jsonPrimitive.content
            val stored = fake.sieveScripts.single { it.id == id }
            assertEquals("corvid-filters", stored.name)
            assertEquals(blobId, stored.blobId)
            assertEquals(listOf("old" to false, id to true), fake.sieveScripts.map { it.id to it.isActive })
        }

    @Test
    fun `an updated script keeps its id and takes the new blob`() =
        runTest {
            val fake = FakeJmapServer()
            fake.sieveScripts += FakeSieveScript(id = "s0", name = "corvid-filters", blobId = "b0")
            val client = connect(fake)

            val result =
                client.sieveSet(
                    buildJsonObject { putJsonObject("update") { putJsonObject("s0") { put("blobId", "b1") } } },
                )

            assertEquals(setOf("s0"), result.getValue("updated").jsonObject.keys)
            assertEquals("b1", fake.sieveScripts.single().blobId)
        }

    @Test
    fun `a script the server cannot compile is reported as invalidSieve for that create`() =
        runTest {
            val fake = FakeJmapServer()
            fake.invalidSieve = true
            val client = connect(fake)

            val result = client.sieveSet(createScript("c1", "b1"))

            val failure = result.getValue("notCreated").jsonObject.getValue("c1").jsonObject
            assertEquals("invalidSieve", failure.getValue("type").jsonPrimitive.content)
            assertEquals(emptyList(), fake.sieveScripts)
        }

    @Test
    fun `a failed create leaves the active foreign script active`() =
        runTest {
            val fake = FakeJmapServer()
            fake.invalidSieve = true
            fake.sieveScripts += FakeSieveScript(id = "old", name = "other", blobId = "b0", isActive = true)
            val client = connect(fake)

            client.sieveSet(createScript("c1", "b1"), activate = "#c1")

            assertEquals(listOf("old" to true), fake.sieveScripts.map { it.id to it.isActive })
        }

    @Test
    fun `get lists every script with its name blob and active flag`() =
        runTest {
            val fake = FakeJmapServer()
            fake.sieveScripts += FakeSieveScript(id = "s0", name = "mine", blobId = "b0", isActive = true)
            val client = connect(fake)

            val outcome = client.call(listOf(Invocation("SieveScript/get", buildJsonObject {}, "c0"))).getValue("c0")

            val script = assertIs<MethodOutcome.Success>(outcome).result.getValue("list").jsonArray.single().jsonObject
            assertEquals("mine", script.getValue("name").jsonPrimitive.content)
            assertEquals("b0", script.getValue("blobId").jsonPrimitive.content)
            assertEquals("true", script.getValue("isActive").jsonPrimitive.content)
        }
}

private fun createScript(
    creationId: String,
    blobId: String,
): JsonObject =
    buildJsonObject {
        putJsonObject("create") {
            putJsonObject(creationId) {
                put("name", "corvid-filters")
                put("blobId", blobId)
            }
        }
    }

private suspend fun JmapClient.sieveSet(
    arguments: JsonObject,
    activate: String? = null,
): JsonObject {
    val withActivation =
        buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            activate?.let { put("onSuccessActivateScript", it) }
        }
    val outcome = call(listOf(Invocation("SieveScript/set", withActivation, "c0"))).getValue("c0")
    return assertIs<MethodOutcome.Success>(outcome).result
}

private suspend fun connect(fake: FakeJmapServer): JmapClient = JmapClient.connect(createJmapHttpClient(fake.engine), AuthLatch(), BASE_URL)
