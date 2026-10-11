package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val SERVER = ServerAddress("https://mail.rig.test:4443")

private const val CREATION =
    """{"challenge":"Y2hhbGxlbmdl","rp":{"id":"rig.test","name":"Corvid"},""" +
        """"user":{"id":"aGFuZGxl","name":"mailuser","displayName":"Mail User"},""" +
        """"pubKeyCredParams":[{"type":"public-key","alg":-7}],""" +
        """"authenticatorSelection":{"residentKey":"preferred","userVerification":"required"},""" +
        """"excludeCredentials":[{"type":"public-key","id":"AQID"}]}"""

private const val REQUEST =
    """{"challenge":"Y2hhbGxlbmdl","rpId":"rig.test","userVerification":"required",""" +
        """"allowCredentials":[{"type":"public-key","id":"AQID"}]}"""

private class FakeKey(
    override var verification: Verification = Verification.Pin,
) : SecurityKey {
    val pins = mutableListOf<String?>()
    val creations = mutableListOf<Creation>()
    val requests = mutableListOf<Request>()
    val hashes = mutableListOf<ByteArray>()
    var closed = false
    var cancels = 0

    var onMake: (String?) -> Made = { Made(byteArrayOf(1, 2, 3), ByteArray(40) { 7 }) }
    var onAssert: (String?) -> Asserted = { Asserted(byteArrayOf(1, 2, 3), ByteArray(37) { 7 }, byteArrayOf(5, 5), null) }

    override fun make(
        creation: Creation,
        clientDataHash: ByteArray,
        pin: String?,
    ): Made {
        creations += creation
        hashes += clientDataHash
        pins += pin
        return onMake(pin)
    }

    override fun assert(
        request: Request,
        clientDataHash: ByteArray,
        pin: String?,
    ): Asserted {
        requests += request
        hashes += clientDataHash
        pins += pin
        return onAssert(pin)
    }

    override fun cancel() {
        cancels++
    }

    override fun close() {
        closed = true
    }
}

/** Types [pins] into the prompt, one for each time it asks, recording what it showed. */
private fun TestScope.typing(
    prompt: SecurityKeyPrompt,
    vararg pins: String,
): MutableList<KeyPromptState> {
    val seen = mutableListOf<KeyPromptState>()
    val queue = pins.toMutableList()
    backgroundScope.launch {
        prompt.state.collect { state ->
            seen += state
            if (state is KeyPromptState.Pin && queue.isNotEmpty()) prompt.submitPin(queue.removeFirst())
        }
    }
    return seen
}

class DesktopAuthenticatorTest {
    private val prompt = SecurityKeyPrompt()
    private val key = FakeKey()

    private fun authenticator(key: SecurityKey = this.key) = DesktopAuthenticator(SecurityKeys { key }, prompt)

    private fun TestScope.signedResponse(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("response").jsonObject

    @Test
    fun `making a passkey asks the PIN, then the touch, and gives the key the hash of the client data it returns`() =
        runTest {
            val seen = typing(prompt, "1234")

            val response = signedResponse(authenticator().create(SERVER, CREATION))

            assertEquals(listOf<String?>("1234"), key.pins)
            assertEquals(listOf(KeyPromptState.Pin(false), KeyPromptState.Touch), seen.filter { it != KeyPromptState.Idle })
            assertEquals(KeyPromptState.Idle, prompt.state.value)
            val clientData = String(decodeBase64Url(response.getValue("clientDataJSON").jsonPrimitive.content))
            assertContentEquals(sha256(clientData), key.hashes.single())
            assertEquals(
                """{"type":"webauthn.create","challenge":"Y2hhbGxlbmdl","origin":"https://mail.rig.test:4443","crossOrigin":false}""",
                clientData,
            )
        }

    @Test
    fun `the key is told the relying party, the user and the credentials to exclude`() =
        runTest {
            typing(prompt, "1234")

            authenticator().create(SERVER, CREATION)

            val creation = key.creations.single()
            assertEquals("rig.test", creation.rpId)
            assertEquals("Corvid", creation.rpName)
            assertEquals("mailuser", creation.userName)
            assertContentEquals("handle".toByteArray(), creation.userId)
            assertContentEquals(byteArrayOf(1, 2, 3), creation.exclude.single())
            assertEquals(false, creation.discoverable)
        }

    @Test
    fun `signing in passes the allowed credentials and answers the signature`() =
        runTest {
            typing(prompt, "1234")

            val response = signedResponse(authenticator().get(SERVER, REQUEST))

            assertContentEquals(byteArrayOf(1, 2, 3), key.requests.single().allow.single())
            assertEquals("rig.test", key.requests.single().rpId)
            assertEquals("BQU", response.getValue("signature").jsonPrimitive.content)
        }

    @Test
    fun `a wrong PIN is asked for again, saying so, and the second one is used`() =
        runTest {
            val seen = typing(prompt, "0000", "1234")
            key.onMake = {
                    pin ->
                if (pin == "0000") throw SecurityKeyException(SecurityKeyError.PinInvalid) else Made(byteArrayOf(1), ByteArray(40))
            }

            authenticator().create(SERVER, CREATION)

            assertEquals(listOf<String?>("0000", "1234"), key.pins)
            assertTrue(KeyPromptState.Pin(true) in seen)
        }

    @Test
    fun `cancelling the PIN prompt is a cancellation and the key is never asked`() =
        runTest {
            backgroundScope.launch { prompt.state.collect { if (it is KeyPromptState.Pin) prompt.cancel() } }

            assertFailsWith<PasskeyException.Cancelled> { authenticator().get(SERVER, REQUEST) }

            assertEquals(emptyList(), key.pins)
            assertTrue(key.closed)
        }

    @Test
    fun `a key that verifies by itself is not asked for a PIN`() =
        runTest {
            val seen = typing(prompt)
            key.verification = Verification.BuiltIn

            authenticator().get(SERVER, REQUEST)

            assertEquals(listOf<String?>(null), key.pins)
            assertTrue(seen.none { it is KeyPromptState.Pin })
        }

    @Test
    fun `a key with neither a PIN nor a fingerprint is refused before it is touched`() =
        runTest {
            key.verification = Verification.None

            val refused = assertFailsWith<PasskeyException.Refused> { authenticator().create(SERVER, CREATION) }

            assertTrue(refused.message!!.contains("PIN"))
            assertEquals(emptyList(), key.pins)
            assertTrue(key.closed)
        }

    @Test
    fun `no key attached is a refusal that says to plug it in`() =
        runTest {
            val none = DesktopAuthenticator(SecurityKeys { null }, prompt)

            assertTrue(assertFailsWith<PasskeyException.Refused> { none.get(SERVER, REQUEST) }.message!!.contains("plug"))
        }

    @Test
    fun `a machine without libfido2 is unsupported`() =
        runTest {
            assertFailsWith<PasskeyException.Unsupported> { DesktopAuthenticator(null, prompt).create(SERVER, CREATION) }
        }

    @Test
    fun `what the key refuses with is what the person is told`() =
        runTest {
            val cases =
                mapOf(
                    SecurityKeyError.NoCredentials to PasskeyException.NoCredential::class,
                    SecurityKeyError.NotTouched to PasskeyException.Cancelled::class,
                    SecurityKeyError.Cancelled to PasskeyException.Cancelled::class,
                    SecurityKeyError.Unsupported to PasskeyException.Unsupported::class,
                    SecurityKeyError.PinBlocked to PasskeyException.Refused::class,
                    SecurityKeyError.PinAuthBlocked to PasskeyException.Refused::class,
                    SecurityKeyError.PinNotSet to PasskeyException.Refused::class,
                    SecurityKeyError.CredentialExcluded to PasskeyException.Refused::class,
                    SecurityKeyError.Other to PasskeyException.Refused::class,
                )
            for ((error, expected) in cases) {
                typing(prompt, "1234")
                val refusing = FakeKey().apply { onAssert = { throw SecurityKeyException(error, "from the key") } }

                val thrown = assertFailsWith<PasskeyException> { authenticator(refusing).get(SERVER, REQUEST) }

                assertEquals(expected, thrown::class, error.name)
                assertTrue(refusing.closed, error.name)
            }
        }

    @Test
    fun `a libfido2 too old to have a function is unsupported`() =
        runTest {
            typing(prompt, "1234")
            key.onAssert = { throw UnsatisfiedLinkError("fido_assert_authdata_ptr") }

            assertFailsWith<PasskeyException.Unsupported> { authenticator().get(SERVER, REQUEST) }

            assertTrue(key.closed)
        }

    @Test
    fun `cancelling while the key waits for a touch ends the wait through the key`() =
        runTest {
            val waiting = CountDownLatch(1)
            val released = CountDownLatch(1)
            val blocked =
                object : SecurityKey by key {
                    override fun assert(
                        request: Request,
                        clientDataHash: ByteArray,
                        pin: String?,
                    ): Asserted {
                        waiting.countDown()
                        released.await(10, TimeUnit.SECONDS)
                        throw SecurityKeyException(SecurityKeyError.Cancelled)
                    }

                    override fun cancel() {
                        key.cancel()
                        released.countDown()
                    }
                }
            backgroundScope.launch {
                prompt.state.collect { state ->
                    if (state is KeyPromptState.Pin) prompt.submitPin("1234")
                    if (state == KeyPromptState.Touch) prompt.cancel()
                }
            }

            assertFailsWith<PasskeyException.Cancelled> { authenticator(blocked).get(SERVER, REQUEST) }

            assertEquals(1, key.cancels)
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
        }

    @Test
    fun `options the app cannot read are a refusal in the server's words`() =
        runTest {
            val refused = assertFailsWith<PasskeyException.Refused> { authenticator().get(SERVER, """{"challenge":"x"}""") }

            assertTrue(refused.message!!.startsWith("the server's options could not be read"))
            assertIs<PasskeyException>(refused)
        }
}
