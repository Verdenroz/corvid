package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.FakeAuthApi
import com.verdenroz.corvid.core.testing.FakeClock
import com.verdenroz.corvid.core.testing.authTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private val TEST_SERVER = ServerAddress("https://mail.corvid.test")

@OptIn(ExperimentalCoroutinesApi::class)
class TokenSourceTest {
    private val clock = FakeClock()
    private val api = FakeAuthApi()

    private fun TestScope.source(latch: AuthLatch = AuthLatch()) = TokenSource(latch, backgroundScope, api, clock)

    @Test
    fun `a source has no bearer token until tokens are adopted`() =
        runTest {
            val source = source()

            assertEquals(AuthState.SignedOut, source.state.value)
            assertNull(source.currentBearerToken())
        }

    @Test
    fun `a fresh token is returned without asking the server`() =
        runTest {
            val source = source()
            source.adopt(authTokens(clock = clock), TEST_SERVER)

            assertEquals("at1", source.currentBearerToken())
            assertEquals(emptyList(), api.calls)
        }

    @Test
    fun `an expired token is refreshed once, and the new one is returned`() =
        runTest {
            api.refresh = { Refreshed.Tokens(authTokens("at2", "rt2", clock = clock)) }
            val source = source()
            source.adopt(authTokens(expiresIn = 0.seconds, clock = clock), TEST_SERVER)

            assertEquals("at2", source.currentBearerToken())
            assertEquals(AuthState.SignedIn("at2", TEST_SERVER), source.state.value)
            assertEquals(listOf("refresh rt1"), api.calls)
        }

    @Test
    fun `the refresh token it sends next is the one the last refresh returned`() =
        runTest {
            api.refresh = { Refreshed.Tokens(authTokens("at2", "rt2", expiresIn = 0.seconds, clock = clock)) }
            val source = source()
            source.adopt(authTokens(expiresIn = 0.seconds, clock = clock), TEST_SERVER)

            source.currentBearerToken()
            source.currentBearerToken()

            assertEquals(listOf("refresh rt1", "refresh rt2"), api.calls)
        }

    @Test
    fun `a token is good until it is within the margin of expiring as time passes`() =
        runTest {
            api.refresh = { Refreshed.Tokens(authTokens("at2", "rt2", clock = clock)) }
            val source = source()
            source.adopt(authTokens(expiresIn = 120.seconds, clock = clock), TEST_SERVER)
            assertEquals("at1", source.currentBearerToken())

            clock += 61.seconds

            assertEquals("at2", source.currentBearerToken())
        }

    @Test
    fun `an invalid_grant on refresh signs out instead of leaving a stale token`() =
        runTest {
            api.refresh = { Refreshed.InvalidGrant }
            val source = source()
            source.adopt(authTokens(expiresIn = 0.seconds, clock = clock), TEST_SERVER)

            assertNull(source.currentBearerToken())
            assertEquals(AuthState.SignedOut, source.state.value)
        }

    @Test
    fun `a token with no refresh token signs out once it expires`() =
        runTest {
            val source = source()
            source.adopt(authTokens(refreshToken = null, expiresIn = 0.seconds, clock = clock), TEST_SERVER)

            assertNull(source.currentBearerToken())
            assertEquals(AuthState.SignedOut, source.state.value)
        }

    @Test
    fun `a network failure on refresh surfaces without signing out, and the next call tries again`() =
        runTest {
            var attempts = 0
            api.refresh = {
                attempts++
                if (attempts == 1) throw JmapException(JmapError.Unreachable(Exception("offline")))
                Refreshed.Tokens(authTokens("at2", "rt2", clock = clock))
            }
            val source = source()
            source.adopt(authTokens(expiresIn = 0.seconds, clock = clock), TEST_SERVER)

            assertFailsWith<JmapException> { source.currentBearerToken() }
            assertEquals(AuthState.SignedIn("at1", TEST_SERVER), source.state.value)
            assertEquals("at2", source.currentBearerToken())
            assertEquals(2, attempts)
        }

    @Test
    fun `callers racing an expired token share a single refresh request`() =
        runTest {
            var refreshes = 0
            api.refresh = {
                refreshes++
                delay(10)
                Refreshed.Tokens(authTokens("at2", "rt2", clock = clock))
            }
            val source = source()
            source.adopt(authTokens(expiresIn = 0.seconds, clock = clock), TEST_SERVER)

            val callers = List(5) { async { source.currentBearerToken() } }

            assertEquals(List(5) { "at2" }, callers.map { it.await() })
            assertEquals(1, refreshes)
        }

    @Test
    fun `the first 401 anywhere wipes the token exactly like an invalid_grant does`() =
        runTest {
            val latch = AuthLatch()
            val source = source(latch)
            source.adopt(authTokens(clock = clock), TEST_SERVER)

            latch.trip()
            advanceUntilIdle()

            assertEquals(AuthState.SignedOut, source.state.value)
            assertNull(source.currentBearerToken())
        }
}
