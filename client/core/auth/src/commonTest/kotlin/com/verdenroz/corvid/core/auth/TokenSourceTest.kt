package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import org.publicvalue.multiplatform.oidc.types.remote.ErrorResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

private fun freshToken(
    accessToken: String = "at1",
    refreshToken: String? = "rt1",
    expiresIn: Int = 3600,
) = AccessTokenResponse(
    access_token = accessToken,
    token_type = "Bearer",
    expires_in = expiresIn,
    refresh_token = refreshToken,
    refresh_token_expires_in = null,
    refresh_expires_in = null,
    id_token = null,
    scope = null,
    received_at = Clock.System.now().epochSeconds,
)

private val TEST_SERVER = ServerAddress("https://mail.corvid.test")

@OptIn(ExperimentalCoroutinesApi::class)
class TokenSourceTest {
    @Test
    fun `a signed-in source has no bearer token until tokens are adopted`() =
        runTest {
            val source = TokenSource(AuthLatch(), backgroundScope)

            assertEquals(AuthState.SignedOut, source.state.value)
            assertNull(source.currentBearerToken())
        }

    @Test
    fun `a fresh token is returned without going through the client at all`() =
        runTest {
            val source = TokenSource(AuthLatch(), backgroundScope)
            source.adopt(FakeOidcClient { error("refresh must not be called") }, freshToken(), TEST_SERVER)

            assertEquals("at1", source.currentBearerToken())
        }

    @Test
    fun `an expired token is refreshed once, and the new one is returned`() =
        runTest {
            val source = TokenSource(AuthLatch(), backgroundScope)
            source.adopt(FakeOidcClient { freshToken(accessToken = "at2") }, freshToken(expiresIn = 0), TEST_SERVER)

            assertEquals("at2", source.currentBearerToken())
            assertEquals(AuthState.SignedIn("at2", TEST_SERVER), source.state.value)
        }

    @Test
    fun `invalid_grant on refresh signs out instead of leaving a stale token`() =
        runTest {
            val failure =
                OpenIdConnectException.UnsuccessfulTokenRequest(
                    message = "invalid_grant",
                    statusCode = HttpStatusCode.BadRequest,
                    errorResponse = ErrorResponse(ErrorResponse.Error.invalid_grant, null, null, null),
                    cause = Exception("invalid_grant"),
                )
            val source = TokenSource(AuthLatch(), backgroundScope)
            source.adopt(FakeOidcClient { throw failure }, freshToken(expiresIn = 0), TEST_SERVER)

            assertNull(source.currentBearerToken())
            assertEquals(AuthState.SignedOut, source.state.value)
        }

    @Test
    fun `a network failure on refresh gives up rather than retrying`() =
        runTest {
            var attempts = 0
            val source = TokenSource(AuthLatch(), backgroundScope)
            source.adopt(
                FakeOidcClient {
                    attempts++
                    throw OpenIdConnectException.TechnicalFailure("offline", Exception("offline"))
                },
                freshToken(expiresIn = 0),
                TEST_SERVER,
            )

            assertNull(source.currentBearerToken())
            assertEquals(1, attempts)
        }

    @Test
    fun `a token expiring inside the refresh margin is refreshed early`() =
        runTest {
            val source = TokenSource(AuthLatch(), backgroundScope)
            source.adopt(FakeOidcClient { freshToken(accessToken = "at2") }, freshToken(expiresIn = 30), TEST_SERVER)

            assertEquals("at2", source.currentBearerToken())
        }

    @Test
    fun `two callers racing an expired token share a single refresh request`() =
        runTest {
            var refreshCount = 0
            val source = TokenSource(AuthLatch(), backgroundScope)
            source.adopt(
                FakeOidcClient {
                    refreshCount++
                    delay(10)
                    freshToken(accessToken = "at2")
                },
                freshToken(expiresIn = 0),
                TEST_SERVER,
            )

            val first = async { source.currentBearerToken() }
            val second = async { source.currentBearerToken() }

            assertEquals(listOf("at2", "at2"), listOf(first.await(), second.await()))
            assertEquals(1, refreshCount)
        }

    @Test
    fun `the first 401 anywhere wipes the token exactly like an invalid_grant does`() =
        runTest {
            val latch = AuthLatch()
            val source = TokenSource(latch, backgroundScope)
            source.adopt(FakeOidcClient { freshToken() }, freshToken(), TEST_SERVER)

            latch.trip()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, source.state.value)
            assertNull(source.currentBearerToken())
        }
}
