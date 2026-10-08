package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.EndSessionFlow
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Clock

private val server = ServerAddress("https://mail.example.test")

private fun token(access: String) =
    AccessTokenResponse(
        access_token = access,
        token_type = "Bearer",
        expires_in = 300,
        refresh_token = null,
        refresh_token_expires_in = null,
        refresh_expires_in = null,
        id_token = null,
        scope = null,
        received_at = Clock.System.now().epochSeconds,
    )

private class RecordingFactory(private val flow: CodeAuthFlow) : CodeAuthFlowFactory {
    var client: OpenIdConnectClient? = null

    override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow {
        this.client = client
        return flow
    }

    override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow = TODO("not used by MasterSignInUseCase")
}

class MasterSignInUseCaseTest {
    private val latch = AuthLatch()

    @Test
    fun `the admin token comes back and the daily session is left as it was`() =
        runTest {
            val tokenSource = signedIn()
            val useCase =
                MasterSignInUseCase(RecordingFactory(FakeCodeAuthFlow { token("admin-at") }), fakeRedirectUriProvider, tokenSource)

            val admin = useCase()

            assertEquals("admin-at", admin)
            assertEquals(AuthState.SignedIn("daily-at", server), tokenSource.state.value)
            assertEquals("daily-at", tokenSource.currentBearerToken())
        }

    @Test
    fun `the flow runs as the admin client at the issuer the session signed in through`() =
        runTest {
            val factory = RecordingFactory(FakeCodeAuthFlow { token("admin-at") })

            MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn())()

            val config = factory.client!!.config
            assertEquals("corvid-admin", config.clientId)
            assertEquals("https://idp.example.test/.well-known/openid-configuration", config.discoveryUri)
            assertEquals(fakeRedirectUriProvider.redirectUri(), config.redirectUri)
        }

    @Test
    fun `a failed passkey sign-in trips nothing`() =
        runTest {
            val tokenSource = signedIn()
            val useCase =
                MasterSignInUseCase(RecordingFactory(FakeCodeAuthFlow { error("cancelled") }), fakeRedirectUriProvider, tokenSource)

            assertFailsWith<IllegalStateException> { useCase() }

            assertFalse(latch.tripped.value)
            assertEquals(AuthState.SignedIn("daily-at", server), tokenSource.state.value)
        }

    @Test
    fun `signing in as the admin needs a signed-in session to name the issuer`() =
        runTest {
            val useCase =
                MasterSignInUseCase(
                    RecordingFactory(FakeCodeAuthFlow { token("admin-at") }),
                    fakeRedirectUriProvider,
                    TokenSource(latch, backgroundScope),
                )

            assertFailsWith<IllegalStateException> { useCase() }
        }

    private fun TestScope.signedIn(): TokenSource =
        TokenSource(latch, backgroundScope).also {
            val client =
                createOidcClient("https://idp.example.test/.well-known/openid-configuration", fakeRedirectUriProvider.redirectUri())
            it.adopt(client, token("daily-at"), server)
        }
}
