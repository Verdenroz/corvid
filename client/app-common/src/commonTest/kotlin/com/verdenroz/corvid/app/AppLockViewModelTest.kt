package com.verdenroz.corvid.app

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_self_failed_not_accepted
import com.verdenroz.corvid.appcommon.resources.enroll_self_failed_signin
import com.verdenroz.corvid.appcommon.resources.unreachable_heartbeat
import com.verdenroz.corvid.appcommon.resources.unreachable_key_damaged
import com.verdenroz.corvid.appcommon.resources.unreachable_key_unreachable
import com.verdenroz.corvid.appcommon.resources.unreachable_request_failed
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.domain.MasterSignIn
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.keyring.DeviceRequest
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.keyring.KeyState
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val BASE_URL = "http://127.0.0.1:9999"

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

/**
 * [AppLockViewModel] does its work on [viewModelScope], a [CoroutineScope]
 * structurally unrelated to runTest's own, so [kotlinx.coroutines.test.TestScope.advanceUntilIdle]
 * cannot see it: awaiting the observable [AppLockState] itself is what
 * actually waits for it. Never wrapped in [kotlinx.coroutines.withTimeout]:
 * that delay is virtual time runTest fast-forwards through on its own,
 * which would fire long before MockEngine's real background dispatcher
 * (HttpClientEngineConfig's default) gets a chance to run.
 */
private suspend fun StateFlow<AppLockState>.awaitState(predicate: (AppLockState) -> Boolean): AppLockState = first(predicate)

private class Harness(
    scope: CoroutineScope,
    fake: FakeJmapServer,
    masterSignIn: MasterSignIn,
    store: InMemoryPreferencesStore = InMemoryPreferencesStore(),
) {
    val devicePreferences = DevicePreferences(store, scope, DeviceSettings())
    val authLatch = AuthLatch()
    val tokenSource = TokenSource(authLatch, scope)
    val keyHolder = KeyHolder()
    val viewModel =
        AppLockViewModel(
            tokenSource = tokenSource,
            keyHolder = keyHolder,
            authLatch = authLatch,
            httpClient = createJmapHttpClient(fake.engine),
            unlockedScopeHolder = UnlockedScopeHolder(koinApplication {}.koin),
            masterSignIn = masterSignIn,
            devicePreferences = devicePreferences,
        )

    fun signIn() = tokenSource.adopt(FakeOidcClient { freshToken() }, freshToken(), ServerAddress(BASE_URL))
}

private fun fakeMasterSignIn(token: suspend () -> String) =
    object : MasterSignIn {
        override suspend fun <T> withAdminToken(use: suspend (String) -> T): T = use(token())
    }

private val POLL = 4.seconds

/** Names the device and waits until its request is open on the server. */
private suspend fun Harness.requestKey(name: String): DeviceRequest {
    signIn()
    viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
    viewModel.submitDeviceName(name)
    val waiting = viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
    return ((waiting as AppLockState.NeedsEnrollment).request as EnrollmentRequest.Open).request
}

private fun Harness.watch(): Job = viewModel.viewModelScope.launch { viewModel.watchRequest() }

/** Names the device, has a master elsewhere approve it, then lets the watch read the key. */
private suspend fun Harness.enrollAndUnlock(
    name: String,
    fake: FakeJmapServer,
) {
    val request = requestKey(name)
    fake.devices.approveElsewhere(request.id)
    watch()
    delay(POLL)
    viewModel.state.awaitState { it is AppLockState.Unlocked }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    // Shared with runTest below so advanceTimeBy can reach the heartbeat's
    // delay(), which runs on viewModelScope (Dispatchers.Main) not on this
    // test's own coroutine.
    private val testDispatcher = UnconfinedTestDispatcher()
    private var harness: Harness? = null

    private fun harness(
        scope: CoroutineScope,
        fake: FakeJmapServer,
        masterSignIn: MasterSignIn = fakeMasterSignIn { fake.devices.masterToken },
        store: InMemoryPreferencesStore = InMemoryPreferencesStore(),
    ) = Harness(scope, fake, masterSignIn, store).also { harness = it }

    private suspend fun Harness.awaitSelfApprovalFailure(): EnrollmentRequest.Open {
        val failed =
            viewModel.state.awaitState {
                ((it as? AppLockState.NeedsEnrollment)?.request as? EnrollmentRequest.Open)?.selfApproval is SelfApproval.Failed
            }
        return assertIs<EnrollmentRequest.Open>(assertIs<AppLockState.NeedsEnrollment>(failed).request)
    }

    @BeforeTest
    fun setMain() = Dispatchers.setMain(testDispatcher)

    // A heartbeat's delay() left running past this test would resolve Dispatchers.Main
    // after resetMain() wipes it, crashing whichever test runs next instead of this one:
    // cancel viewModelScope and wait for it to actually finish before moving on, since
    // cancellation is cooperative and a coroutine mid-dispatch on MockEngine's real
    // background thread can otherwise still be unwinding when the next test starts.
    @AfterTest
    fun resetMain() {
        harness?.viewModel?.viewModelScope?.let { scope ->
            scope.cancel()
            runBlocking { scope.coroutineContext.job.join() }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `a signed-in device with no key yet is asked to name itself`() =
        runTest(testDispatcher) {
            val harness = harness(backgroundScope, FakeJmapServer())
            harness.signIn()

            val connecting =
                assertIs<AppLockState.Connecting>(
                    harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName },
                )
            assertTrue(connecting.needsDeviceName)
        }

    @Test
    fun `a device name given once is not asked for again by a fresh view model`() =
        runTest(testDispatcher) {
            val store = InMemoryPreferencesStore()
            val fake = FakeJmapServer()
            val master = fakeMasterSignIn { fake.devices.masterToken }
            Harness(backgroundScope, fake, master, store).also { harness = it }.requestKey("laptop-1")

            val reopened = Harness(backgroundScope, fake, master, store)
            reopened.signIn()
            reopened.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }

            assertEquals(listOf("laptop-1", "laptop-1"), fake.devices.opened)
            reopened.viewModel.viewModelScope.cancel()
        }

    @Test
    fun `a stored name belongs to its server, so another server still asks`() =
        runTest(testDispatcher) {
            val store = InMemoryPreferencesStore()
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, store = store)
            harness.devicePreferences.setDeviceName("other.example.com", "laptop-1")
            harness.signIn()

            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
        }

    @Test
    fun `a missing key opens a request and waits for approval`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)

            val request = harness.requestKey("laptop-1")

            val waiting = assertIs<AppLockState.NeedsEnrollment>(harness.viewModel.state.value)
            assertEquals("laptop-1", waiting.deviceName)
            assertEquals(listOf("laptop-1"), fake.devices.opened)
            assertEquals(fake.devices.codeOf(request.id), request.code)
            assertEquals(0, fake.fileNodes.size)
            assertIs<KeyState.Locked>(harness.keyHolder.state.value)
        }

    @Test
    fun `a pending request keeps waiting`() =
        runTest(testDispatcher) {
            val harness = harness(backgroundScope, FakeJmapServer())
            harness.requestKey("laptop-1")
            harness.watch()

            delay(POLL * 3)

            assertIs<EnrollmentRequest.Open>(assertIs<AppLockState.NeedsEnrollment>(harness.viewModel.state.value).request)
            assertIs<KeyState.Locked>(harness.keyHolder.state.value)
        }

    @Test
    fun `an expired request offers asking again, which opens a fresh one`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            val first = harness.requestKey("laptop-1")
            fake.devices.expire(first.id)
            harness.watch()
            delay(POLL)
            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Expired }

            harness.viewModel.askAgain()
            val reopened =
                harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }

            val second = ((reopened as AppLockState.NeedsEnrollment).request as EnrollmentRequest.Open).request
            assertNotEquals(first.id, second.id)
            assertEquals(listOf("laptop-1", "laptop-1"), fake.devices.opened)
        }

    @Test
    fun `too many open requests is said so and can be asked again`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            fake.devices.full = true
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }

            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.TooManyOpen }

            fake.devices.full = false
            harness.viewModel.askAgain()
            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
        }

    @Test
    fun `a server with device approval off is said so, not called unreachable`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            fake.devices.off = true
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }

            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.DeviceApprovalOff }
        }

    @Test
    fun `a request that never reaches the sentinel reads as unreachable, not approval off`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            fake.devices.unrouted = true
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }

            harness.viewModel.submitDeviceName("laptop-1")
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals(uiText(Res.string.unreachable_request_failed), unreachable.reason)
        }

    @Test
    fun `asking again once device approval is back on opens a request`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            fake.devices.off = true
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.DeviceApprovalOff }

            fake.devices.off = false
            harness.viewModel.askAgain()

            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
            assertEquals(listOf("laptop-1"), fake.devices.opened)
        }

    @Test
    fun `signing out from device approval off reaches the sign-in screen`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            fake.devices.off = true
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.DeviceApprovalOff }

            harness.viewModel.signOut()

            harness.viewModel.state.awaitState { it is AppLockState.SignedOut }
            assertIs<AuthState.SignedOut>(harness.tokenSource.state.value)
        }

    @Test
    fun `taps on ask again while a request is opening open one request`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            fake.devices.full = true
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.TooManyOpen }

            fake.devices.full = false
            repeat(3) { harness.viewModel.askAgain() }

            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
            assertEquals(listOf("laptop-1"), fake.devices.opened)
        }

    @Test
    fun `device approval switched off while a request is open reads as off, not expired`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.requestKey("laptop-1")
            fake.devices.off = true
            harness.watch()

            delay(POLL)

            harness.viewModel.state.awaitState { it is AppLockState.DeviceApprovalOff }
        }

    @Test
    fun `a watch that stops asking leaves an approval unread until it resumes`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            val request = harness.requestKey("laptop-1")
            val watching = harness.watch()
            watching.cancel()
            fake.devices.approveElsewhere(request.id)

            delay(POLL * 3)

            assertIs<AppLockState.NeedsEnrollment>(harness.viewModel.state.value)
            harness.watch()
            delay(POLL)
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }
        }

    @Test
    fun `a key approved elsewhere unlocks the device`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)

            harness.enrollAndUnlock("laptop-1", fake)

            assertIs<KeyState.Unlocked>(harness.keyHolder.state.value)
        }

    @Test
    fun `a device approves its own request with the admin token and then unlocks`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            val request = harness.requestKey("laptop-1")

            harness.viewModel.approveWithAdminPasskey()
            harness.watch()
            delay(POLL)
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }

            val approval = fake.devices.approvals.single()
            assertEquals(request.id, approval.id)
            assertEquals(request.code, approval.code)
            assertEquals(fake.devices.masterToken, approval.master)
            assertIs<KeyState.Unlocked>(harness.keyHolder.state.value)
        }

    @Test
    fun `a token the server does not accept leaves the request open with a message`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, fakeMasterSignIn { "stale" })
            val request = harness.requestKey("laptop-1")

            harness.viewModel.approveWithAdminPasskey()
            val open = harness.awaitSelfApprovalFailure()

            assertEquals(SelfApproval.Failed(uiText(Res.string.enroll_self_failed_not_accepted)), open.selfApproval)
            assertEquals(request, open.request)
        }

    @Test
    fun `a cancelled admin sign-in sends nothing and leaves the request open with a message`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val cancelled = fakeMasterSignIn { throw OpenIdConnectException.AuthenticationCancelled("cancelled") }
            val harness = harness(backgroundScope, fake, cancelled)
            harness.requestKey("laptop-1")

            harness.viewModel.approveWithAdminPasskey()
            val open = harness.awaitSelfApprovalFailure()

            assertEquals(SelfApproval.Failed(uiText(Res.string.enroll_self_failed_signin)), open.selfApproval)
            assertEquals(emptyList(), fake.devices.approvals)
        }

    @Test
    fun `approving a request that has ended offers asking again`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            val request = harness.requestKey("laptop-1")
            fake.devices.expire(request.id)

            harness.viewModel.approveWithAdminPasskey()

            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Expired }
        }

    @Test
    fun `an unreachable key lookup is not mistaken for a revoked device`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }

            fake.unreachable = true
            harness.viewModel.submitDeviceName("laptop-1")
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals(uiText(Res.string.unreachable_key_unreachable), unreachable.reason)
        }

    @Test
    fun `a damaged key file reads as unreachable, not revoked, and nothing is wiped`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            fake.blobs[fake.devices.blobIdOf("laptop-1")] = ByteArray(10)
            harness.viewModel.unlock()
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals(uiText(Res.string.unreachable_key_damaged), unreachable.reason)
        }

    @Test
    fun `a key missing after this session already held one reads as revoked and signs out`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            fake.devices.revoke("laptop-1")
            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.Revoked }

            assertIs<AuthState.SignedOut>(harness.tokenSource.state.value)
        }

    @Test
    fun `a device that held a key and then finds approval off is not revoked and stays signed in`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            fake.devices.off = true
            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.DeviceApprovalOff }

            assertIs<AuthState.SignedIn>(harness.tokenSource.state.value)
        }

    @Test
    fun `the lock button drops the key and a 401 anywhere does the same`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)

            harness.viewModel.lock()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }

            harness.authLatch.trip()
            harness.keyHolder.state.first { it is KeyState.Locked }
        }

    @Test
    fun `a heartbeat failure reads as unreachable, not a user lock`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)

            fake.unreachable = true
            advanceTimeBy(61.seconds)
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals(uiText(Res.string.unreachable_heartbeat), unreachable.reason)
            assertIs<KeyState.Locked>(harness.keyHolder.state.value)
        }
}
