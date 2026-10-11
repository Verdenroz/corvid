package com.verdenroz.corvid.feature.signin

import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.domain.DiscoveryDocumentSource
import com.verdenroz.corvid.core.domain.SignIn
import com.verdenroz.corvid.core.domain.SrvLookup
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.RememberedAccount
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_no_address
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_unreachable
import com.verdenroz.corvid.feature.signin.resources.signin_issuer_not_https
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val DOCUMENT = """{"version":1,"server":"https://mail.example.com:4443","issuer":"https://idp.example.com:4443"}"""
private val DISCOVERY = Discovery(ServerAddress("https://mail.example.com:4443"), "https://idp.example.com:4443")

private val REMEMBERED = RememberedAccount("https://mail.example.com:4443", "https://idp.example.com:4443", "mailuser@example.com")

private fun preferences() = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Main), DeviceSettings())

private fun discoverAccount(documents: Map<String, String> = mapOf("https://example.com/.well-known/corvid" to DOCUMENT)) =
    DiscoverAccount(srvLookup = SrvLookup { null }, documents = DiscoveryDocumentSource { documents[it] })

class SignInViewModelTest {
    @Test
    fun `an address with a server behind it moves to the confirmation`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences()))

            viewModel.discover("mailuser@example.com")

            assertEquals(SignInUiState.Discovered(DISCOVERY), viewModel.state.value)
            assertEquals("mail.example.com", viewModel.state.value.discovery?.serverHost)
            assertEquals("idp.example.com", viewModel.state.value.discovery?.issuerHost)
        }

    @Test
    fun `an unreachable domain names itself in the failure`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(emptyMap()), SignIn { _, _ -> }, preferences()))

            viewModel.discover("mailuser@example.com")

            assertEquals(
                SignInUiState.DiscoveryFailed(uiText(Res.string.signin_discovery_unreachable, "example.com")),
                viewModel.state.value,
            )
        }

    @Test
    fun `an address with no domain fails before any lookup`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences()))

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
                        preferences(),
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
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> gate.await() }, preferences()))

            viewModel.signIn("https://mail.example.com", "https://idp.example.com")

            assertNull(viewModel.state.value.discovery)
            gate.complete(Unit)
        }

    @Test
    fun `an http issuer in the advanced fields is refused`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> throw IllegalArgumentException() }, preferences()))

            viewModel.signIn("https://mail.example.com", "http://idp.example.com")

            assertEquals(SignInUiState.Failed(uiText(Res.string.signin_issuer_not_https)), viewModel.state.value)
        }

    @Test
    fun `reset leaves the confirmation`() =
        runViewModelTest {
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences()))
            viewModel.discover("mailuser@example.com")

            viewModel.reset()

            assertEquals(SignInUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `a discovered account is remembered with its address once sign-in succeeds`() =
        runViewModelTest {
            val preferences = preferences()
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences))
            viewModel.discover(" mailuser@example.com ")

            viewModel.signIn("https://mail.example.com:4443/", "https://idp.example.com:4443/")

            assertEquals(REMEMBERED, preferences.settings.value.account)
            assertEquals(REMEMBERED, viewModel.remembered.value)
        }

    @Test
    fun `an advanced sign-in is remembered without an address`() =
        runViewModelTest {
            val preferences = preferences()
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences))

            viewModel.signIn("https://mail.example.org/", "https://idp.example.org")

            assertEquals(RememberedAccount("https://mail.example.org", "https://idp.example.org"), preferences.settings.value.account)
        }

    @Test
    fun `a discovered server changed under Advanced is remembered without the address`() =
        runViewModelTest {
            val preferences = preferences()
            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences))
            viewModel.discover("mailuser@example.com")

            viewModel.signIn("https://other.example.com:4443", "https://idp.example.com:4443")

            assertEquals(
                RememberedAccount("https://other.example.com:4443", "https://idp.example.com:4443"),
                preferences.settings.value.account,
            )
        }

    @Test
    fun `the remembered account is offered as soon as the screen opens`() =
        runViewModelTest {
            val preferences = preferences()
            preferences.rememberAccount(REMEMBERED)

            val viewModel = track(SignInViewModel(discoverAccount(), SignIn { _, _ -> }, preferences))

            assertEquals(REMEMBERED, viewModel.remembered.value)
        }

    @Test
    fun `signing in as the remembered account goes straight to the provider and keeps its address`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val signedIn = mutableListOf<Pair<ServerAddress, String>>()
            val preferences = preferences()
            preferences.rememberAccount(REMEMBERED)
            val viewModel =
                track(
                    SignInViewModel(
                        discoverAccount(emptyMap()),
                        SignIn { server, issuer ->
                            signedIn += server to issuer
                            gate.await()
                        },
                        preferences,
                    ),
                )

            viewModel.signInAs(REMEMBERED)

            assertEquals(SignInUiState.SigningIn(null), viewModel.state.value)
            gate.complete(Unit)
            assertEquals(SignInUiState.Idle, viewModel.state.value)
            assertEquals(listOf(ServerAddress("https://mail.example.com:4443") to "https://idp.example.com:4443"), signedIn)
            assertEquals(REMEMBERED, preferences.settings.value.account)
        }

    @Test
    fun `a failed sign-in as another account keeps the remembered one offered`() =
        runViewModelTest {
            val preferences = preferences()
            preferences.rememberAccount(REMEMBERED)
            val refused = SignIn { _, _ -> throw OpenIdConnectException.AuthenticationFailure("denied") }
            val viewModel = track(SignInViewModel(discoverAccount(), refused, preferences))

            viewModel.signIn("https://mail.example.org", "https://idp.example.org")

            assertIs<SignInUiState.Failed>(viewModel.state.value)
            assertEquals(REMEMBERED, viewModel.remembered.value)
        }
}
