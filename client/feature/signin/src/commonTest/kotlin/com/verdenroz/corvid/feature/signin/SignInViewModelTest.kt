package com.verdenroz.corvid.feature.signin

import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.domain.DiscoveryDocumentSource
import com.verdenroz.corvid.core.domain.EnrollPasskey
import com.verdenroz.corvid.core.domain.SignIn
import com.verdenroz.corvid.core.domain.SrvLookup
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
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
import com.verdenroz.corvid.feature.signin.resources.signin_server_not_https
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val DOCUMENT = """{"version":2,"server":"https://mail.example.com:4443"}"""
private val DISCOVERY = Discovery(ServerAddress("https://mail.example.com:4443"), "mailuser@example.com")

private val REMEMBERED = RememberedAccount("https://mail.example.com:4443", "mailuser@example.com")

private fun preferences() = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Main), DeviceSettings())

private fun discoverAccount(documents: Map<String, String> = mapOf("https://example.com/.well-known/corvid" to DOCUMENT)) =
    DiscoverAccount(srvLookup = SrvLookup { null }, documents = DiscoveryDocumentSource { documents[it] })

private val NO_ENROLL = EnrollPasskey { _, _, _ -> error("no enrollment was asked for") }

private fun viewModel(
    signIn: SignIn = SignIn { },
    enroll: EnrollPasskey = NO_ENROLL,
    preferences: DevicePreferences = preferences(),
    documents: Map<String, String> = mapOf("https://example.com/.well-known/corvid" to DOCUMENT),
) = SignInViewModel(discoverAccount(documents), signIn, enroll, preferences)

class SignInViewModelTest {
    @Test
    fun `an address with a server behind it moves to the confirmation`() =
        runViewModelTest {
            val viewModel = track(viewModel())

            viewModel.discover("mailuser@example.com")

            assertEquals(SignInUiState.Discovered(DISCOVERY), viewModel.state.value)
            assertEquals("mail.example.com", viewModel.state.value.discovery?.serverHost)
        }

    @Test
    fun `an unreachable domain names itself in the failure`() =
        runViewModelTest {
            val viewModel = track(viewModel(documents = emptyMap()))

            viewModel.discover("mailuser@example.com")

            assertEquals(
                SignInUiState.DiscoveryFailed(uiText(Res.string.signin_discovery_unreachable, "example.com")),
                viewModel.state.value,
            )
        }

    @Test
    fun `an address with no domain fails before any lookup`() =
        runViewModelTest {
            val viewModel = track(viewModel())

            viewModel.discover("mailuser")

            assertEquals(SignInUiState.DiscoveryFailed(uiText(Res.string.signin_discovery_no_address)), viewModel.state.value)
        }

    @Test
    fun `signing in keeps the discovery on screen until the prompt ends`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val signedIn = mutableListOf<ServerAddress>()
            val viewModel =
                track(
                    viewModel(
                        SignIn { server ->
                            signedIn += server
                            gate.await()
                        },
                    ),
                )
            viewModel.discover("mailuser@example.com")

            viewModel.confirm(DISCOVERY)

            assertEquals(SignInUiState.SigningIn(DISCOVERY), viewModel.state.value)
            gate.complete(Unit)
            assertEquals(SignInUiState.Idle, viewModel.state.value)
            assertEquals(listOf(ServerAddress("https://mail.example.com:4443")), signedIn)
        }

    @Test
    fun `an advanced sign-in carries no discovery`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val viewModel = track(viewModel(SignIn { gate.await() }))

            viewModel.signIn("https://mail.example.com")

            assertNull(viewModel.state.value.discovery)
            gate.complete(Unit)
        }

    @Test
    fun `an http server in the advanced field is refused before anything is sent`() =
        runViewModelTest {
            val viewModel = track(viewModel(SignIn { error("must not sign in") }))

            viewModel.signIn("http://mail.example.com")

            assertEquals(SignInUiState.Failed(uiText(Res.string.signin_server_not_https)), viewModel.state.value)
        }

    @Test
    fun `reset leaves the confirmation`() =
        runViewModelTest {
            val viewModel = track(viewModel())
            viewModel.discover("mailuser@example.com")

            viewModel.reset()

            assertEquals(SignInUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `a discovered account is remembered with its address once sign-in succeeds`() =
        runViewModelTest {
            val preferences = preferences()
            val viewModel = track(viewModel(preferences = preferences))
            viewModel.discover(" mailuser@example.com ")

            viewModel.confirm((viewModel.state.value as SignInUiState.Discovered).discovery)

            assertEquals(REMEMBERED, preferences.settings.value.account)
            assertEquals(REMEMBERED, viewModel.remembered.value)
        }

    @Test
    fun `an advanced sign-in is remembered without an address`() =
        runViewModelTest {
            val preferences = preferences()
            val viewModel = track(viewModel(preferences = preferences))

            viewModel.signIn("https://mail.example.org/")

            assertEquals(RememberedAccount("https://mail.example.org"), preferences.settings.value.account)
        }

    @Test
    fun `the remembered account is offered as soon as the screen opens`() =
        runViewModelTest {
            val preferences = preferences()
            preferences.rememberAccount(REMEMBERED)

            val viewModel = track(viewModel(preferences = preferences))

            assertEquals(REMEMBERED, viewModel.remembered.value)
        }

    @Test
    fun `signing in as the remembered account goes straight to the prompt and keeps its address`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val signedIn = mutableListOf<ServerAddress>()
            val preferences = preferences()
            preferences.rememberAccount(REMEMBERED)
            val viewModel =
                track(
                    viewModel(
                        SignIn { server ->
                            signedIn += server
                            gate.await()
                        },
                        preferences = preferences,
                        documents = emptyMap(),
                    ),
                )

            viewModel.setUp(REMEMBERED, "")

            assertEquals(SignInUiState.SigningIn(null), viewModel.state.value)
            gate.complete(Unit)
            assertEquals(SignInUiState.Idle, viewModel.state.value)
            assertEquals(listOf(ServerAddress("https://mail.example.com:4443")), signedIn)
            assertEquals(REMEMBERED, preferences.settings.value.account)
        }

    @Test
    fun `a dismissed prompt says so and keeps the remembered account offered`() =
        runViewModelTest {
            val preferences = preferences()
            preferences.rememberAccount(REMEMBERED)
            val viewModel = track(viewModel(SignIn { throw PasskeyException.Cancelled }, preferences = preferences))

            viewModel.signIn("https://mail.example.org")

            assertEquals(SignInUiState.Failed(PasskeyException.Cancelled.userMessage()), viewModel.state.value)
            assertEquals(REMEMBERED, viewModel.remembered.value)
        }

    @Test
    fun `each way the prompt can fail has its own message`() =
        runViewModelTest {
            val failures =
                listOf(
                    PasskeyException.NotAssociated,
                    PasskeyException.Unsupported,
                    PasskeyException.Refused("blocked"),
                )
            for (failure in failures) {
                val viewModel = track(viewModel(SignIn { throw failure }))

                viewModel.signIn("https://mail.example.org")

                assertEquals(SignInUiState.Failed(failure.userMessage()), viewModel.state.value)
            }
        }

    @Test
    fun `a device with no passkey for the account offers setting it up`() =
        runViewModelTest {
            val viewModel = track(viewModel(SignIn { throw PasskeyException.NoCredential }))

            viewModel.signIn("https://mail.example.org")

            assertEquals(
                SignInUiState.Failed(PasskeyException.NoCredential.userMessage(), FailureHint.SetUp),
                viewModel.state.value,
            )
        }

    @Test
    fun `a server with no passkey enrolled offers setting this device up`() =
        runViewModelTest {
            val viewModel = track(viewModel(SignIn { throw AuthFailure.NotEnrolled }))

            viewModel.signIn("https://mail.example.org")

            assertEquals(SignInUiState.Failed(AuthFailure.NotEnrolled.userMessage(), FailureHint.SetUp), viewModel.state.value)
        }

    @Test
    fun `an unreachable server is a failure, not a crash`() =
        runViewModelTest {
            val down = JmapException(JmapError.Unreachable(Exception("down")))
            val viewModel = track(viewModel(SignIn { throw down }))

            viewModel.signIn("https://mail.example.org")

            assertEquals(SignInUiState.Failed(down.userMessage()), viewModel.state.value)
        }

    @Test
    fun `a code makes the passkey, which signs in without a second prompt`() =
        runViewModelTest {
            val steps = mutableListOf<String>()
            val viewModel =
                track(
                    viewModel(
                        SignIn { error("enrolling already signed in") },
                        EnrollPasskey { _, code, _ ->
                            steps += "enroll $code"
                            false
                        },
                    ),
                )

            viewModel.setUp(RememberedAccount("https://mail.example.com"), " ABCD-EFGH ")

            assertEquals(listOf("enroll ABCD-EFGH"), steps)
            assertEquals(SignInUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `a code that signs in is remembered like any other sign-in`() =
        runViewModelTest {
            val viewModel = track(viewModel(SignIn { error("enrolling already signed in") }, EnrollPasskey { _, _, _ -> true }))

            viewModel.setUp(RememberedAccount("https://mail.example.com"), "ABCD-EFGH")

            assertEquals("https://mail.example.com", viewModel.remembered.value?.server)
        }

    @Test
    fun `a code the server refuses stops before sign-in and asks for a new one`() =
        runViewModelTest {
            val viewModel =
                track(viewModel(SignIn { error("must not sign in") }, EnrollPasskey { _, _, _ -> throw AuthFailure.BadCode }))

            viewModel.setUp(RememberedAccount("https://mail.example.com"), "nope")

            assertEquals(SignInUiState.Failed(AuthFailure.BadCode.userMessage(), FailureHint.NewCode), viewModel.state.value)
        }

    @Test
    fun `setting up a remembered account enrolls at its server and keeps it remembered`() =
        runViewModelTest {
            val enrolled = mutableListOf<Pair<ServerAddress, String>>()
            val viewModel =
                track(
                    viewModel(
                        SignIn { error("enrolling already signed in") },
                        EnrollPasskey { server, code, _ ->
                            enrolled += server to code
                            false
                        },
                    ),
                )

            viewModel.setUp(REMEMBERED, "ABCD-EFGH")

            assertEquals(listOf(ServerAddress(REMEMBERED.server) to "ABCD-EFGH"), enrolled)
            assertEquals(REMEMBERED, viewModel.remembered.value)
        }

    @Test
    fun `a blank code asks for no enrollment`() =
        runViewModelTest {
            val viewModel = track(viewModel())

            viewModel.setUp(RememberedAccount("https://mail.example.com"), "   ")

            assertIs<SignInUiState.Idle>(viewModel.state.value)
        }

    @Test
    fun `a server still switching passkeys on shows finishing setup until it accepts the session`() =
        runViewModelTest {
            val seen = mutableListOf<SignInUiState>()
            lateinit var viewModel: SignInViewModel
            viewModel =
                track(
                    viewModel(
                        SignIn { error("enrolling already signed in") },
                        EnrollPasskey { _, _, onWaiting ->
                            onWaiting()
                            seen += viewModel.state.value
                            true
                        },
                    ),
                )

            viewModel.setUp(RememberedAccount("https://mail.example.com"), "ABCD-EFGH")

            assertEquals(listOf<SignInUiState>(SignInUiState.FinishingSetup), seen)
            assertEquals(SignInUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `a session the server never accepts says setup is pending and still remembers the account`() =
        runViewModelTest {
            val viewModel =
                track(
                    viewModel(
                        SignIn { error("must not sign in") },
                        EnrollPasskey { _, _, _ -> throw AuthFailure.SetupPending },
                    ),
                )

            viewModel.setUp(RememberedAccount("https://mail.example.com"), "ABCD-EFGH")

            assertEquals(SignInUiState.Failed(AuthFailure.SetupPending.userMessage()), viewModel.state.value)
            assertEquals("https://mail.example.com", viewModel.remembered.value?.server)
        }
}
