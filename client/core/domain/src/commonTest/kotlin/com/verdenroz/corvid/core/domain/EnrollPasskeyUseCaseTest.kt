package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.FakeAuthApi
import com.verdenroz.corvid.core.testing.FakeAuthenticator
import com.verdenroz.corvid.core.testing.authTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SERVER = ServerAddress("https://mail.example.test")

class EnrollPasskeyUseCaseTest {
    private val latch = AuthLatch()
    private val api = FakeAuthApi(authTokens("at-from-register"))
    private val authenticator = FakeAuthenticator()

    private fun TestScope.enroll(tokenSource: TokenSource) = EnrollPasskeyUseCase(api, authenticator, tokenSource, latch)

    @Test
    fun `the code's options go to the prompt and the credential it makes goes back`() =
        runTest {
            api.registersApprover = true

            val approves = enroll(TokenSource(latch, backgroundScope, api))(SERVER, "ABCD-EFGH") {}

            assertTrue(approves)
            assertEquals(listOf("create creation-options:ABCD-EFGH"), authenticator.calls)
            assertEquals(listOf(SERVER), authenticator.servers)
            assertEquals(
                listOf("registerOptions ABCD-EFGH", "registerFinish created:creation-options:ABCD-EFGH", "mailAccepts at-from-register"),
                api.calls,
            )
        }

    @Test
    fun `registering signs in with the tokens it returned and no second prompt`() =
        runTest {
            val tokenSource = TokenSource(latch, backgroundScope, api)

            enroll(tokenSource)(SERVER, "ABCD-EFGH") {}

            assertEquals(AuthState.SignedIn("at-from-register", SERVER), tokenSource.state.value)
            assertEquals(listOf("create creation-options:ABCD-EFGH"), authenticator.calls)
        }

    @Test
    fun `registering clears a latch tripped by an earlier 401`() =
        runTest {
            latch.trip()

            enroll(TokenSource(latch, backgroundScope, api))(SERVER, "ABCD-EFGH") {}

            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a code the server refuses never reaches the prompt`() =
        runTest {
            api.optionsFailure = AuthFailure.BadCode

            assertFailsWith<AuthFailure.BadCode> { enroll(TokenSource(latch, backgroundScope, api))(SERVER, "x") {} }

            assertEquals(emptyList(), authenticator.calls)
        }

    @Test
    fun `a dismissed prompt finishes nothing and signs nobody in`() =
        runTest {
            authenticator.failure = PasskeyException.Cancelled
            val tokenSource = TokenSource(latch, backgroundScope, api)

            assertFailsWith<PasskeyException.Cancelled> { enroll(tokenSource)(SERVER, "ABCD-EFGH") {} }

            assertEquals(listOf("registerOptions ABCD-EFGH"), api.calls)
            assertEquals(AuthState.SignedOut, tokenSource.state.value)
        }

    @Test
    fun `the session is adopted only once the mail server accepts its token, and waiting is announced once`() =
        runTest {
            var polls = 0
            api.mailAccepts = { ++polls > 3 }
            val tokenSource = TokenSource(latch, backgroundScope, api)
            var announced = 0

            enroll(tokenSource)(SERVER, "ABCD-EFGH") { announced++ }

            assertEquals(4, polls)
            assertEquals(1, announced)
            assertEquals(AuthState.SignedIn("at-from-register", SERVER), tokenSource.state.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a session the mail server never accepts is polled twelve times 15 seconds apart, not adopted, and says setup is pending`() =
        runTest {
            api.mailAccepts = { false }
            val tokenSource = TokenSource(latch, backgroundScope, api)

            assertFailsWith<AuthFailure.SetupPending> { enroll(tokenSource)(SERVER, "ABCD-EFGH") {} }

            assertEquals(AuthState.SignedOut, tokenSource.state.value)
            assertEquals(12, api.calls.count { it.startsWith("mailAccepts") })
            assertEquals(11 * 15_000L, testScheduler.currentTime)
        }

    @Test
    fun `a server that already accepts the token is not waited for`() =
        runTest {
            var announced = false

            enroll(TokenSource(latch, backgroundScope, api))(SERVER, "ABCD-EFGH") { announced = true }

            assertFalse(announced)
            assertEquals(1, api.calls.count { it.startsWith("mailAccepts") })
        }
}
