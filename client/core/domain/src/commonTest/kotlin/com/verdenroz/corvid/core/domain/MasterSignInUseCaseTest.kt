package com.verdenroz.corvid.core.domain

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

class MasterSignInUseCaseTest {
    private val latch = AuthLatch()
    private val api = FakeAuthApi(authTokens("admin-at", refreshToken = null))
    private val authenticator = FakeAuthenticator()

    private fun TestScope.signedIn(): TokenSource =
        TokenSource(latch, backgroundScope, api).also { it.adopt(authTokens("daily-at"), SERVER) }

    private fun master(tokenSource: TokenSource) = MasterSignInUseCase(api, authenticator, tokenSource)

    @Test
    fun `the admin token comes back and the daily session is left as it was`() =
        runTest {
            val tokenSource = signedIn()

            val admin = master(tokenSource).withAdminToken { it }

            assertEquals("admin-at", admin)
            assertEquals(AuthState.SignedIn("daily-at", SERVER), tokenSource.state.value)
            assertEquals("daily-at", tokenSource.currentBearerToken())
        }

    @Test
    fun `only the master ceremony runs`() =
        runTest {
            master(signedIn()).withAdminToken { }

            assertEquals(listOf("loginOptions master", "loginFinish asserted:request-options:master"), api.calls)
            assertEquals(listOf("get request-options:master"), authenticator.calls)
            assertEquals(listOf(SERVER), authenticator.servers)
        }

    @Test
    fun `the block's exception surfaces`() =
        runTest {
            val failure = assertFailsWith<IllegalStateException> { master(signedIn()).withAdminToken<Unit> { error("refused") } }

            assertEquals("refused", failure.message)
        }

    @Test
    fun `a dismissed prompt gives the block nothing and trips nothing`() =
        runTest {
            authenticator.failure = PasskeyException.Cancelled
            val tokenSource = signedIn()
            var ran = false

            assertFailsWith<PasskeyException.Cancelled> { master(tokenSource).withAdminToken { ran = true } }

            assertFalse(ran)
            assertFalse(latch.tripped.value)
            assertEquals(AuthState.SignedIn("daily-at", SERVER), tokenSource.state.value)
        }

    @Test
    fun `signing in as the master needs a signed-in session to name the server`() =
        runTest {
            val useCase = master(TokenSource(latch, backgroundScope, api))

            assertFailsWith<IllegalStateException> { useCase.withAdminToken { } }
        }
}
