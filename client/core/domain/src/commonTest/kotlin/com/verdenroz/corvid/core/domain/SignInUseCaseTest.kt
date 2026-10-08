package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.RedirectUriProvider
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.test.runTest
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Clock

internal val fakeRedirectUriProvider =
    object : RedirectUriProvider {
        override fun redirectUri() = "http://127.0.0.1:0/callback"
    }

private fun freshToken() =
    AccessTokenResponse(
        access_token = "at1",
        token_type = "Bearer",
        expires_in = 3600,
        refresh_token = "rt1",
        refresh_token_expires_in = null,
        refresh_expires_in = null,
        id_token = null,
        scope = null,
        received_at = Clock.System.now().epochSeconds,
    )

class SignInUseCaseTest {
    @Test
    fun `a successful sign-in clears a latch tripped by an earlier 401`() =
        runTest {
            val latch = AuthLatch()
            latch.trip()
            val tokenSource = TokenSource(latch, backgroundScope)
            val useCase =
                SignInUseCase(
                    authFlowFactory = FakeCodeAuthFlowFactory(FakeCodeAuthFlow { freshToken() }),
                    redirectUriProvider = fakeRedirectUriProvider,
                    tokenSource = tokenSource,
                    authLatch = latch,
                )

            useCase(ServerAddress("https://mail.example.test"), "https://idp.example.test")

            assertFalse(latch.tripped.value)
            assertEquals(AuthState.SignedIn("at1", ServerAddress("https://mail.example.test")), tokenSource.state.value)
        }

    @Test
    fun `a trailing slash on the issuer is trimmed before appending the discovery path`() {
        assertEquals(
            "https://idp.example.test/.well-known/openid-configuration",
            discoveryUri("https://idp.example.test/"),
        )
    }

    @Test
    fun `an http issuer is refused`() {
        assertFailsWith<IllegalArgumentException> { discoveryUri("http://idp.example.test") }
    }
}
