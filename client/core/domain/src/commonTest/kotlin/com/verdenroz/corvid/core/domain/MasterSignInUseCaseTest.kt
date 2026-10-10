package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import io.ktor.http.URLBuilder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
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

private fun token(
    access: String,
    idToken: String? = null,
) = AccessTokenResponse(
    access_token = access,
    token_type = "Bearer",
    expires_in = 300,
    refresh_token = null,
    refresh_token_expires_in = null,
    refresh_expires_in = null,
    id_token = idToken,
    scope = null,
    received_at = Clock.System.now().epochSeconds,
)

private class RecordingFactory(
    private val flow: CodeAuthFlow,
    private val endSession: (clientId: String, idToken: String?) -> Unit = { _, _ -> },
) : CodeAuthFlowFactory {
    val events = mutableListOf<String>()
    var client: OpenIdConnectClient? = null

    override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow {
        this.client = client
        events += "authorize ${client.config.clientId}"
        return flow
    }

    override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow =
        object : EndSessionFlow {
            override suspend fun endSession(
                idToken: String?,
                configureEndSessionUrl: (URLBuilder.() -> Unit)?,
            ) {
                events += "end-session ${client.config.clientId} $idToken"
                endSession(client.config.clientId!!, idToken)
            }

            override suspend fun startLogout(
                idToken: String?,
                configureEndSessionUrl: (URLBuilder.() -> Unit)?,
            ) = TODO("endSession is overridden")

            override suspend fun canContinueLogout(): Boolean = TODO("endSession is overridden")

            override suspend fun continueLogout() = TODO("endSession is overridden")
        }
}

class MasterSignInUseCaseTest {
    private val latch = AuthLatch()

    @Test
    fun `the admin token comes back and the daily session is left as it was`() =
        runTest {
            val tokenSource = signedIn()
            val useCase =
                MasterSignInUseCase(RecordingFactory(FakeCodeAuthFlow { token("admin-at") }), fakeRedirectUriProvider, tokenSource)

            val admin = useCase.withAdminToken { it }

            assertEquals("admin-at", admin)
            assertEquals(AuthState.SignedIn("daily-at", server), tokenSource.state.value)
            assertEquals("daily-at", tokenSource.currentBearerToken())
        }

    @Test
    fun `the admin session ends after the token is used`() =
        runTest {
            val factory = RecordingFactory(FakeCodeAuthFlow { token("admin-at", idToken = "admin-id") })

            MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn()).withAdminToken { factory.events += "use $it" }

            assertEquals(
                listOf(
                    "end-session corvid daily-id",
                    "authorize corvid-admin",
                    "use admin-at",
                    "end-session corvid-admin admin-id",
                ),
                factory.events,
            )
        }

    @Test
    fun `the admin session ends when the block throws and the exception surfaces`() =
        runTest {
            val factory = RecordingFactory(FakeCodeAuthFlow { token("admin-at", idToken = "admin-id") })

            val failure =
                assertFailsWith<IllegalStateException> {
                    MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn()).withAdminToken<Unit> { error("refused") }
                }

            assertEquals("refused", failure.message)
            assertEquals("end-session corvid-admin admin-id", factory.events.last())
        }

    @Test
    fun `a failed admin sign-out does not mask the block's exception`() =
        runTest {
            val factory =
                RecordingFactory(
                    FakeCodeAuthFlow { token("admin-at") },
                    endSession = { clientId, _ ->
                        if (clientId == "corvid-admin") throw OpenIdConnectException.AuthenticationCancelled("cancelled")
                    },
                )

            val failure =
                assertFailsWith<IllegalStateException> {
                    MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn()).withAdminToken<Unit> { error("refused") }
                }

            assertEquals("refused", failure.message)
        }

    @Test
    fun `a cancelled first sign-out throws and never authorizes`() =
        runTest {
            val factory =
                RecordingFactory(FakeCodeAuthFlow { token("admin-at") }, endSession = {
                        _,
                        _,
                    ->
                    throw OpenIdConnectException.AuthenticationCancelled("cancelled")
                })

            assertFailsWith<OpenIdConnectException> { MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn()).withAdminToken { } }

            assertEquals(listOf("end-session corvid daily-id"), factory.events)
        }

    @Test
    fun `a failed admin sign-out still returns the block result`() =
        runTest {
            val factory =
                RecordingFactory(
                    FakeCodeAuthFlow { token("admin-at") },
                    endSession = {
                            clientId,
                            _,
                        ->
                        if (clientId == "corvid-admin") throw OpenIdConnectException.AuthenticationCancelled("cancelled")
                    },
                )

            assertEquals("used admin-at", MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn()).withAdminToken { "used $it" })
        }

    @Test
    fun `the flow runs as the admin client at the issuer the session signed in through`() =
        runTest {
            val factory = RecordingFactory(FakeCodeAuthFlow { token("admin-at") })

            MasterSignInUseCase(factory, fakeRedirectUriProvider, signedIn()).withAdminToken { }

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

            assertFailsWith<IllegalStateException> { useCase.withAdminToken { } }

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

            assertFailsWith<IllegalStateException> { useCase.withAdminToken { } }
        }

    private fun TestScope.signedIn(): TokenSource =
        TokenSource(latch, backgroundScope).also {
            val client =
                createOidcClient("https://idp.example.test/.well-known/openid-configuration", fakeRedirectUriProvider.redirectUri())
            it.adopt(client, token("daily-at", idToken = "daily-id"), server)
        }
}
