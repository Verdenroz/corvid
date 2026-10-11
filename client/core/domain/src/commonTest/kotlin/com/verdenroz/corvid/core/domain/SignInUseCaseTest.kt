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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private val SERVER = ServerAddress("https://mail.example.test")

class SignInUseCaseTest {
    private val latch = AuthLatch()
    private val api = FakeAuthApi(authTokens("daily-at"))
    private val authenticator = FakeAuthenticator()

    private fun TestScope.signIn(tokenSource: TokenSource) = SignInUseCase(api, authenticator, tokenSource, latch)

    @Test
    fun `the server's options go to the prompt and its assertion goes back for tokens`() =
        runTest {
            val tokenSource = TokenSource(latch, backgroundScope, api)

            signIn(tokenSource)(SERVER)

            assertEquals(listOf("get request-options:daily"), authenticator.calls)
            assertEquals(listOf("loginOptions daily", "loginFinish asserted:request-options:daily"), api.calls)
            assertEquals(AuthState.SignedIn("daily-at", SERVER), tokenSource.state.value)
        }

    @Test
    fun `a successful sign-in clears a latch tripped by an earlier 401`() =
        runTest {
            latch.trip()
            val tokenSource = TokenSource(latch, backgroundScope, api)

            signIn(tokenSource)(SERVER)

            assertFalse(latch.tripped.value)
        }

    @Test
    fun `a dismissed prompt signs nobody in and trips nothing`() =
        runTest {
            authenticator.failure = PasskeyException.Cancelled
            val tokenSource = TokenSource(latch, backgroundScope, api)

            assertFailsWith<PasskeyException.Cancelled> { signIn(tokenSource)(SERVER) }

            assertEquals(AuthState.SignedOut, tokenSource.state.value)
            assertEquals(listOf("loginOptions daily"), api.calls)
        }

    @Test
    fun `a server with no daily passkey enrolled stops before the prompt`() =
        runTest {
            api.optionsFailure = AuthFailure.NotEnrolled
            val tokenSource = TokenSource(latch, backgroundScope, api)

            assertFailsWith<AuthFailure.NotEnrolled> { signIn(tokenSource)(SERVER) }

            assertEquals(emptyList(), authenticator.calls)
        }

    @Test
    fun `an assertion the server refuses leaves the session signed out`() =
        runTest {
            api.finishFailure = AuthFailure.Refused
            val tokenSource = TokenSource(latch, backgroundScope, api)

            assertFailsWith<AuthFailure.Refused> { signIn(tokenSource)(SERVER) }

            assertEquals(AuthState.SignedOut, tokenSource.state.value)
        }
}
