package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enrolls and signs in with the attached security key against the real sentinel binary, through
 * the real [DesktopAuthenticator] and [HttpAuthApi]. It needs a person to touch the key twice, so
 * it runs only with CORVID_FIDO2_LIVE=1; the key's PIN comes from CORVID_FIDO2_PIN and the binary
 * from CORVID_SENTINEL_BIN (default: target/debug/corvid-sentinel at the repository root).
 *
 *   cargo build -p corvid-sentinel
 *   CORVID_FIDO2_LIVE=1 CORVID_FIDO2_PIN=... ./gradlew :core:auth:cleanDesktopTest \
 *     :core:auth:desktopTest --tests '*Fido2LiveTest*' -i
 */
class Fido2LiveTest {
    private val secret = "live-test-secret-0123456789"

    @Test
    fun `a real security key enrolls and signs in against the real sentinel`() {
        if (System.getenv("CORVID_FIDO2_LIVE") != "1") {
            println("skipped: set CORVID_FIDO2_LIVE=1 with a key attached to run this")
            return
        }
        val pin = checkNotNull(System.getenv("CORVID_FIDO2_PIN")) { "CORVID_FIDO2_PIN is not set" }
        val binary = File(System.getenv("CORVID_SENTINEL_BIN") ?: "../../../target/debug/corvid-sentinel").canonicalFile
        check(binary.canExecute()) { "no sentinel at $binary; run cargo build -p corvid-sentinel" }

        val dir = Files.createTempDirectory("corvid-live").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val config = File(dir, "config.yaml")
        config.writeText(
            """
            server: {domain: rig.test, hostname: mail.rig.test, mail_user: mailuser, acme_contact: ops@rig.test, web_port: 4443}
            sentinel:
              bind: 127.0.0.1:$port
              state: ${dir.path}/sentinel.json
              auth: {state: ${dir.path}/auth.json}
            """.trimIndent(),
        )
        val sentinel =
            ProcessBuilder(binary.path)
                .apply {
                    environment()["CORVID_CONFIG"] = config.path
                    environment()["CORVID_SENTINEL_SECRET"] = secret
                    redirectErrorStream(true)
                    redirectOutput(File(dir, "sentinel.log"))
                }.start()
        try {
            run(port, pin)
        } finally {
            sentinel.destroy()
            dir.deleteRecursively()
        }
    }

    private fun run(
        port: Int,
        pin: String,
    ) {
        val http = HttpClient.newHttpClient()
        val base = "http://127.0.0.1:$port"
        repeat(50) { if (!listening(http, base)) Thread.sleep(100) }
        check(listening(http, base)) { "the sentinel never answered on $base" }

        val code =
            Json.parseToJsonElement(signed(http, "$base/internal/auth/codes/daily", "code daily")).jsonObject
                .getValue("code").jsonPrimitive.content
        val loopback = ServerAddress(base)
        // The sentinel lists https://mail.rig.test:4443 as the one origin; the ceremony is signed for it.
        val origin = ServerAddress("https://mail.rig.test:4443")
        val api = HttpAuthApi(createJmapHttpClient())
        val prompt = SecurityKeyPrompt()
        val authenticator = DesktopAuthenticator(prompt)

        runBlocking {
            val watcher =
                launch {
                    prompt.state.collect { state ->
                        when (state) {
                            is KeyPromptState.Pin -> if (state.wrong) prompt.cancel() else prompt.submitPin(pin)
                            KeyPromptState.Touch -> println("TOUCH THE SECURITY KEY NOW")
                            KeyPromptState.Idle -> Unit
                        }
                    }
                }
            val role = api.registerFinish(loopback, authenticator.create(origin, api.registerOptions(loopback, code)))
            assertEquals(PasskeyRole.Daily, role)

            val tokens = api.loginFinish(loopback, authenticator.get(origin, api.loginOptions(loopback, PasskeyRole.Daily)))
            watcher.cancel()

            assertTrue(verifiesAgainstJwks(http, base, tokens.accessToken), "the access token does not verify against the issuer's key set")
            assertEquals(200, userinfo(http, base, tokens.accessToken))
        }
    }

    private fun listening(
        http: HttpClient,
        base: String,
    ): Boolean =
        runCatching { http.send(get("$base/corvid/auth/jwks"), HttpResponse.BodyHandlers.ofString()).statusCode() == 200 }
            .getOrDefault(false)

    private fun get(url: String): HttpRequest = HttpRequest.newBuilder(URI(url)).GET().build()

    private fun signed(
        http: HttpClient,
        url: String,
        message: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray()))
        val request = HttpRequest.newBuilder(URI(url)).header("X-Signature", signature).POST(HttpRequest.BodyPublishers.noBody()).build()
        val reply = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(reply.statusCode() == 200) { "minting a code answered ${reply.statusCode()}" }
        return reply.body()
    }

    private fun userinfo(
        http: HttpClient,
        base: String,
        token: String,
    ): Int =
        http
            .send(
                HttpRequest.newBuilder(URI("$base/corvid/auth/userinfo")).header("Authorization", "Bearer $token").GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    private fun verifiesAgainstJwks(
        http: HttpClient,
        base: String,
        jwt: String,
    ): Boolean {
        val (header, payload, signature) = jwt.split('.')
        val kid = Json.parseToJsonElement(String(decodeBase64Url(header))).jsonObject.getValue("kid").jsonPrimitive.content
        val jwks = Json.parseToJsonElement(http.send(get("$base/corvid/auth/jwks"), HttpResponse.BodyHandlers.ofString()).body())
        val jwk =
            (
                jwks.jsonObject.getValue(
                    "keys",
                ) as JsonArray
            ).map { it.jsonObject }.first { it.getValue("kid").jsonPrimitive.content == kid }
        val point =
            ECPoint(
                BigInteger(1, decodeBase64Url(jwk.getValue("x").jsonPrimitive.content)),
                BigInteger(1, decodeBase64Url(jwk.getValue("y").jsonPrimitive.content)),
            )
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val key =
            KeyFactory.getInstance(
                "EC",
            ).generatePublic(ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec::class.java)))
        return Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(key)
            update("$header.$payload".toByteArray())
            verify(decodeBase64Url(signature))
        }
    }
}
