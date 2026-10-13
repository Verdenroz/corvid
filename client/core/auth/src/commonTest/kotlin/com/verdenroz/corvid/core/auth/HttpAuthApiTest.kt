package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.FakeClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private val SERVER = ServerAddress("https://mail.corvid.test:4443")
private val JSON = headersOf(HttpHeaders.ContentType, listOf("application/json"))

class HttpAuthApiTest {
    private val clock = FakeClock()
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: MockRequestHandler) =
        HttpAuthApi(
            createJmapHttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ),
            clock,
        )

    private fun HttpRequestData.sent() = (body as TextContent).text

    @Test
    fun `registration options are asked for with the code and come back untouched`() =
        runTest {
            val api = api { respond("""{"challenge":"c"}""", HttpStatusCode.OK, JSON) }

            assertEquals("""{"challenge":"c"}""", api.registerOptions(SERVER, "ABCD-EFGH"))

            assertEquals(HttpMethod.Post, requests.single().method)
            assertEquals("https://mail.corvid.test:4443/corvid/auth/register/options", requests.single().url.toString())
            assertEquals("""{"code":"ABCD-EFGH"}""", requests.single().sent())
        }

    @Test
    fun `a code the server does not know is a bad code`() =
        runTest {
            val api = api { respond("", HttpStatusCode.Unauthorized) }

            assertIs<AuthFailure.BadCode>(assertFailsWith<AuthFailure> { api.registerOptions(SERVER, "x") })
        }

    @Test
    fun `finishing a registration sends the credential as it came and reads the sign-in it made`() =
        runTest {
            val api =
                api {
                    respond(
                        """{"access_token":"at","token_type":"Bearer","expires_in":900,"refresh_token":"rt","approves":true}""",
                        HttpStatusCode.OK,
                        JSON,
                    )
                }

            val registered = api.registerFinish(SERVER, """{"id":"abc"}""")

            assertEquals(Registered(AuthTokens("at", "rt", clock.now() + 900.seconds), approves = true), registered)

            assertEquals("""{"id":"abc"}""", requests.single().sent())
            assertEquals("https://mail.corvid.test:4443/corvid/auth/register/finish", requests.single().url.toString())
        }

    @Test
    fun `a credential the server already holds is reported as enrolled`() =
        runTest {
            val api = api { respond("", HttpStatusCode.Conflict) }

            assertIs<AuthFailure.AlreadyEnrolled>(assertFailsWith<AuthFailure> { api.registerFinish(SERVER, "{}") })
        }

    @Test
    fun `sign-in options name the purpose`() =
        runTest {
            val api = api { respond("""{"challenge":"c"}""", HttpStatusCode.OK, JSON) }

            api.loginOptions(SERVER, Purpose.Approve)

            assertEquals("""{"purpose":"approve"}""", requests.single().sent())
            assertEquals("https://mail.corvid.test:4443/corvid/auth/login/options", requests.single().url.toString())
        }

    @Test
    fun `a purpose no passkey can serve is not enrolled`() =
        runTest {
            val api = api { respond("", HttpStatusCode.NotFound) }

            assertIs<AuthFailure.NotEnrolled>(assertFailsWith<AuthFailure> { api.loginOptions(SERVER, Purpose.SignIn) })
        }

    @Test
    fun `a sign-in answers tokens that expire expires_in seconds from now`() =
        runTest {
            val api =
                api {
                    respond(
                        """{"access_token":"at","token_type":"Bearer","expires_in":900,"refresh_token":"rt"}""",
                        HttpStatusCode.OK,
                        JSON,
                    )
                }

            val tokens = api.loginFinish(SERVER, "{}")

            assertEquals(AuthTokens("at", "rt", clock.now() + 900.seconds), tokens)
        }

    @Test
    fun `an approval answers no refresh token`() =
        runTest {
            val api =
                api { respond("""{"access_token":"at","token_type":"Bearer","expires_in":300}""", HttpStatusCode.OK, JSON) }

            assertNull(api.loginFinish(SERVER, "{}").refreshToken)
        }

    @Test
    fun `an assertion the server refuses is refused`() =
        runTest {
            val api = api { respond("", HttpStatusCode.Unauthorized) }

            assertIs<AuthFailure.Refused>(assertFailsWith<AuthFailure> { api.loginFinish(SERVER, "{}") })
        }

    @Test
    fun `a refresh sends the token and answers the new tokens`() =
        runTest {
            val api =
                api {
                    respond(
                        """{"access_token":"at2","token_type":"Bearer","expires_in":900,"refresh_token":"rt2"}""",
                        HttpStatusCode.OK,
                        JSON,
                    )
                }

            val refreshed = assertIs<Refreshed.Tokens>(api.refresh(SERVER, "rt1"))

            assertEquals(AuthTokens("at2", "rt2", clock.now() + 900.seconds), refreshed.tokens)
            assertEquals("""{"refresh_token":"rt1"}""", requests.single().sent())
            assertEquals("https://mail.corvid.test:4443/corvid/auth/token", requests.single().url.toString())
        }

    @Test
    fun `an invalid_grant is a spent refresh token, not a failure`() =
        runTest {
            val api = api { respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, JSON) }

            assertEquals(Refreshed.InvalidGrant, api.refresh(SERVER, "rt1"))
        }

    @Test
    fun `any other bad request on refresh is a failure, not a sign-out`() =
        runTest {
            val api = api { respond("""{"error":"invalid_request"}""", HttpStatusCode.BadRequest, JSON) }

            assertIs<JmapError.Request>(assertFailsWith<JmapException> { api.refresh(SERVER, "rt1") }.error)
        }

    @Test
    fun `a server with passkeys switched off is off on the ceremony routes`() =
        runTest {
            val api = api { respond("""{"error":"auth_off"}""", HttpStatusCode.ServiceUnavailable, JSON) }

            assertIs<AuthFailure.Off>(assertFailsWith<AuthFailure> { api.registerOptions(SERVER, "x") })
            assertIs<AuthFailure.Off>(assertFailsWith<AuthFailure> { api.loginOptions(SERVER, Purpose.SignIn) })
        }

    @Test
    fun `a refresh the server cannot serve is a server error, not a spent token`() =
        runTest {
            val api = api { respond("""{"error":"auth_off"}""", HttpStatusCode.ServiceUnavailable, JSON) }

            assertEquals(JmapError.Server(503), assertFailsWith<JmapException> { api.refresh(SERVER, "rt") }.error)
        }

    @Test
    fun `a rate limit carries the server's retry-after`() =
        runTest {
            val api = api { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) }

            val error = assertFailsWith<JmapException> { api.registerOptions(SERVER, "x") }.error

            assertEquals(JmapError.RateLimited(30), error)
        }

    @Test
    fun `a server error is a server error`() =
        runTest {
            val api = api { respond("", HttpStatusCode.BadGateway) }

            assertEquals(JmapError.Server(502), assertFailsWith<JmapException> { api.loginFinish(SERVER, "{}") }.error)
        }

    @Test
    fun `a dropped connection is unreachable and a failed handshake is a TLS failure`() =
        runTest {
            val dropped = api { throw IOException("down") }
            val untrusted = api { throw SSLHandshakeException("no") }

            assertIs<JmapError.Unreachable>(assertFailsWith<JmapException> { dropped.loginOptions(SERVER, Purpose.SignIn) }.error)
            assertIs<JmapError.TlsFailure>(assertFailsWith<JmapException> { untrusted.loginOptions(SERVER, Purpose.SignIn) }.error)
        }

    @Test
    fun `an answer that is not JSON is a malformed request, not a crash`() =
        runTest {
            val api = api { respond("<html>", HttpStatusCode.OK) }

            assertIs<JmapError.Request>(assertFailsWith<JmapException> { api.loginFinish(SERVER, "{}") }.error)
        }

    @Test
    fun `the mail server accepting a token is a 200 on the session carrying it as a bearer`() =
        runTest {
            val api = api { respond("{}", HttpStatusCode.OK, JSON) }

            assertEquals(true, api.mailAccepts(SERVER, "at"))

            assertEquals("https://mail.corvid.test:4443/jmap/session", requests.single().url.toString())
            assertEquals("Bearer at", requests.single().headers["Authorization"])
        }

    @Test
    fun `a refused token or a server still restarting is not yet accepted`() =
        runTest {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.BadGateway)) {
                val api = api { respond("", status) }

                assertEquals(false, api.mailAccepts(SERVER, "at"), status.toString())
            }
        }
}
