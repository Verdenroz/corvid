package com.verdenroz.corvid.feature.signin

import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.domain.DiscoveryDocumentSource
import com.verdenroz.corvid.core.domain.SignIn
import com.verdenroz.corvid.core.domain.SrvLookup
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_no_address
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_unreachable
import com.verdenroz.corvid.feature.signin.resources.signin_issuer_not_https
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val DOCUMENT = """{"version":1,"server":"https://mail.example.com:4443","issuer":"https://idp.example.com:4443"}"""
private val DISCOVERY = Discovery(ServerAddress("https://mail.example.com:4443"), "https://idp.example.com:4443")

private fun discoverAccount(documents: Map<String, String> = mapOf("https://example.com/.well-known/corvid" to DOCUMENT)) =
    DiscoverAccount(srvLookup = SrvLookup { null }, documents = DiscoveryDocumentSource { documents[it] })

class SignInViewModelTest {
    @Test
    fun `an address with a server behind it moves to the confirmation`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }))

            viewModel.discover("mailuser@example.com")

            assertEquals(SignInUiState.Discovered(DISCOVERY), viewModel.state.value)
            assertEquals("mail.example.com", viewModel.state.value.discovery?.serverHost)
            assertEquals("idp.example.com", viewModel.state.value.discovery?.issuerHost)
        }

    @Test
    fun `an unreachable domain names itself in the failure`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(emptyMap()), SignIn { _, _ -> }))

            viewModel.discover("mailuser@example.com")

            assertEquals(
                SignInUiState.DiscoveryFailed(uiText(Res.string.signin_discovery_unreachable, "example.com")),
                viewModel.state.value,
            )
        }

    @Test
    fun `an address with no domain fails before any lookup`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }))

            viewModel.discover("mailuser")

            assertEquals(SignInUiState.DiscoveryFailed(uiText(Res.string.signin_discovery_no_address)), viewModel.state.value)
        }

    @Test
    fun `signing in keeps the discovery on screen until the flow ends`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val signedIn = mutableListOf<Pair<ServerAddress, String>>()
            val viewModel =
                track(
                    SignInViewModel(
                        discoverAccount(),
                        SignIn { server, issuer ->
                            signedIn += server to issuer
                            gate.await()
                        },
                    ),
                )
            viewModel.discover("mailuser@example.com")

            viewModel.signIn("https://mail.example.com:4443/", "https://idp.example.com:4443")

            assertEquals(SignInUiState.SigningIn(DISCOVERY), viewModel.state.value)
            gate.complete(Unit)
            assertEquals(SignInUiState.Idle, viewModel.state.value)
            assertEquals(listOf(ServerAddress("https://mail.example.com:4443") to "https://idp.example.com:4443"), signedIn)
        }

    @Test
    fun `an advanced sign-in carries no discovery`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> gate.await() }))

            viewModel.signIn("https://mail.example.com", "https://idp.example.com")

            assertNull(viewModel.state.value.discovery)
            gate.complete(Unit)
        }

    @Test
    fun `an http issuer in the advanced fields is refused`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> throw IllegalArgumentException() }))

            viewModel.signIn("https://mail.example.com", "http://idp.example.com")

            assertEquals(SignInUiState.Failed(uiText(Res.string.signin_issuer_not_https)), viewModel.state.value)
        }

    @Test
    fun `reset leaves the confirmation`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }))
            viewModel.discover("mailuser@example.com")

            viewModel.reset()

            assertEquals(SignInUiState.Idle, viewModel.state.value)
        }
}
