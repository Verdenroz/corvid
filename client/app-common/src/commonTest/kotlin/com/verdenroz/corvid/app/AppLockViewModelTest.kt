package com.verdenroz.corvid.app

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_self_failed_not_accepted
import com.verdenroz.corvid.appcommon.resources.unreachable_heartbeat
import com.verdenroz.corvid.appcommon.resources.unreachable_key_unreachable
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.common.Connectivity
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.keyring.DeviceRequest
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.keyring.KeyState
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.testing.FakeAuthApi
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.authTokens
import com.verdenroz.corvid.core.text.uiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.dsl.koinApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val BASE_URL = "http://127.0.0.1:9999"

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

class FakeConnectivity(online: Boolean = true, override val eventDriven: Boolean = true) : Connectivity {
    private val _online = MutableStateFlow(online)
    override val online: StateFlow<Boolean> = _online

    fun set(online: Boolean) {
        _online.value = online
    }

    /** A drop and a return, each seen by collectors, since a state flow conflates changes made without a suspension. */
    suspend fun flap() {
        yield()
        set(false)
        yield()
        set(true)
        yield()
    }
}

private class Harness(
    scope: CoroutineScope,
    fake: FakeJmapServer,
    approveWithKey: ApproveWithKey,
    store: InMemoryPreferencesStore = InMemoryPreferencesStore(),
    outboxUnlock: OutboxUnlock = OutboxUnlock { _, _, _ -> },
    outboxResume: OutboxResume = OutboxResume { },
    val connectivity: FakeConnectivity = FakeConnectivity(),
) {
    val devicePreferences = DevicePreferences(store, scope, DeviceSettings())
    val authLatch = AuthLatch()
    val tokenSource = TokenSource(authLatch, scope, FakeAuthApi())
    val keyHolder = KeyHolder()
    val viewModel =
        AppLockViewModel(
            tokenSource = tokenSource,
            keyHolder = keyHolder,
            authLatch = authLatch,
            httpClient = createJmapHttpClient(fake.engine),
            unlockedScopeHolder = UnlockedScopeHolder(koinApplication {}.koin),
            approveWithKey = approveWithKey,
            devicePreferences = devicePreferences,
            outboxUnlock = outboxUnlock,
            outboxResume = outboxResume,
            connectivity = connectivity,
        )

    fun signIn() = tokenSource.adopt(authTokens(), ServerAddress(BASE_URL))
}

private fun fakeApproveWithKey(token: suspend () -> String) =
    object : ApproveWithKey {
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
        approveWithKey: ApproveWithKey = fakeApproveWithKey { fake.devices.masterToken },
        store: InMemoryPreferencesStore = InMemoryPreferencesStore(),
        outboxUnlock: OutboxUnlock = OutboxUnlock { _, _, _ -> },
        outboxResume: OutboxResume = OutboxResume { },
        connectivity: FakeConnectivity = FakeConnectivity(),
    ) = Harness(scope, fake, approveWithKey, store, outboxUnlock, outboxResume, connectivity).also { harness = it }

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
            val master = fakeApproveWithKey { fake.devices.masterToken }
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

            assertEquals(uiText(Res.string.unreachable_key_unreachable), unreachable.reason)
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
            val harness = harness(backgroundScope, fake, fakeApproveWithKey { "stale" })
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
            val cancelled = fakeApproveWithKey { throw PasskeyException.Cancelled }
            val harness = harness(backgroundScope, fake, cancelled)
            harness.requestKey("laptop-1")

            harness.viewModel.approveWithAdminPasskey()
            val open = harness.awaitSelfApprovalFailure()

            assertEquals(SelfApproval.Failed(PasskeyException.Cancelled.userMessage()), open.selfApproval)
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
    fun `a damaged key file reads as damaged, not unreachable or revoked, and nothing is wiped`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            fake.blobs[fake.devices.blobIdOf("laptop-1")] = ByteArray(10)
            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.KeyDamaged }
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
    fun `a bare 404 for a key this session already held is unreachable and stays signed in`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            fake.devices.unrouted = true
            harness.viewModel.unlock()
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals(uiText(Res.string.unreachable_key_unreachable), unreachable.reason)
            assertIs<AuthState.SignedIn>(harness.tokenSource.state.value)
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
    fun `a sign-out while the outbox opens is not overwritten by the unlock`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness =
                harness(
                    backgroundScope,
                    fake,
                    outboxUnlock =
                        OutboxUnlock { _, _, _ ->
                            entered.complete(Unit)
                            release.await()
                        },
                )
            val request = harness.requestKey("laptop-1")
            fake.devices.approveElsewhere(request.id)
            harness.watch()
            delay(POLL)
            entered.await()

            harness.viewModel.signOut()
            harness.viewModel.state.awaitState { it is AppLockState.SignedOut }
            release.complete(Unit)
            yield()

            assertIs<AppLockState.SignedOut>(harness.viewModel.state.value)
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

    private suspend fun Harness.reachUnreachable(fake: FakeJmapServer) {
        fake.devices.unrouted = true
        signIn()
        viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
        viewModel.submitDeviceName("laptop-1")
        viewModel.state.awaitState { it is AppLockState.Unreachable }
    }

    @Test
    fun `the network returning while unreachable retries once`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, connectivity = FakeConnectivity(online = false))
            harness.reachUnreachable(fake)

            fake.devices.unrouted = false
            harness.connectivity.flap()

            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
            harness.connectivity.flap()
            advanceTimeBy(10.seconds)
            assertEquals(listOf("laptop-1"), fake.devices.opened)
        }

    @Test
    fun `a network that flaps while unreachable starts one retry`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, connectivity = FakeConnectivity(online = false))
            harness.reachUnreachable(fake)

            fake.devices.unrouted = false
            repeat(4) { harness.connectivity.flap() }

            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
            advanceTimeBy(10.seconds)
            assertEquals(listOf("laptop-1"), fake.devices.opened)
        }

    @Test
    fun `the network returning never unlocks from the lock pane`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            harness.viewModel.lock()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            harness.connectivity.flap()
            advanceTimeBy(10.seconds)

            assertIs<AppLockState.Locked>(harness.viewModel.state.value)
            assertIs<KeyState.Locked>(harness.keyHolder.state.value)
        }

    @Test
    fun `the network returning waits while a message is being written`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, connectivity = FakeConnectivity(online = false))
            harness.reachUnreachable(fake)
            harness.viewModel.writingOffline(true)

            fake.devices.unrouted = false
            harness.connectivity.flap()
            advanceTimeBy(10.seconds)
            assertIs<AppLockState.Unreachable>(harness.viewModel.state.value)

            harness.viewModel.writingOffline(false)
            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
        }

    @Test
    fun `the network returning while unlocked sends the outbox`() =
        runTest(testDispatcher) {
            val resumed = CompletableDeferred<Unit>()
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, outboxResume = OutboxResume { resumed.complete(Unit) })
            harness.enrollAndUnlock("laptop-1", fake)

            harness.connectivity.flap()

            resumed.await()
        }

    @Test
    fun `the network returning while locked does not send the outbox until it is unlocked again`() =
        runTest(testDispatcher) {
            var resumes = 0
            val resumed = CompletableDeferred<Unit>()
            val fake = FakeJmapServer()
            val harness =
                harness(
                    backgroundScope,
                    fake,
                    outboxResume =
                        OutboxResume {
                            resumes++
                            resumed.complete(Unit)
                        },
                )
            harness.enrollAndUnlock("laptop-1", fake)
            harness.viewModel.lock()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }
            harness.connectivity.flap()
            assertEquals(0, resumes)

            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }
            harness.connectivity.flap()
            resumed.await()

            assertEquals(1, resumes)
        }

    @Test
    fun `the network returning after a heartbeat dropped the key does not retry`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            fake.unreachable = true
            advanceTimeBy(61.seconds)
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })
            assertTrue(unreachable.keyDropped)

            fake.unreachable = false
            harness.connectivity.flap()
            advanceTimeBy(10.minutes)

            assertIs<AppLockState.Unreachable>(harness.viewModel.state.value)
            assertIs<KeyState.Locked>(harness.keyHolder.state.value)
        }

    @Test
    fun `the network returning after the first connect failed retries once`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, connectivity = FakeConnectivity(online = false))
            fake.unreachable = true
            harness.signIn()
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })
            assertFalse(unreachable.keyDropped)

            fake.unreachable = false
            harness.connectivity.flap()

            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
        }

    @Test
    fun `entering unreachable with the network already back retries once without waiting for an edge`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            val seen = mutableListOf<AppLockState>()
            backgroundScope.launch { harness.viewModel.state.collect { seen += it } }
            harness.reachUnreachable(fake)
            val connecting = seen.count { it is AppLockState.Connecting }

            fake.devices.unrouted = false
            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }

            assertEquals(listOf("laptop-1"), fake.devices.opened)
            assertTrue(connecting >= 1)
        }

    @Test
    fun `a failed automatic retry waits for the network to drop and return before another`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.reachUnreachable(fake)

            while (fake.devices.lookups.size < 2) withContext(Dispatchers.Default) { delay(20) }
            advanceTimeBy(10.minutes)
            withContext(Dispatchers.Default) { delay(300) }

            assertEquals(listOf("laptop-1", "laptop-1"), fake.devices.lookups)
        }

    @Test
    fun `a tap during an automatic retry does not start a second run`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake, connectivity = FakeConnectivity(online = false))
            harness.reachUnreachable(fake)

            fake.devices.unrouted = false
            harness.connectivity.flap()
            harness.viewModel.retry()
            harness.viewModel.unlock()

            harness.viewModel.state.awaitState { it is AppLockState.NeedsEnrollment && it.request is EnrollmentRequest.Open }
            advanceTimeBy(10.seconds)
            assertEquals(listOf("laptop-1"), fake.devices.opened)
        }

    @Test
    fun `unlocking twice leaves one heartbeat loop`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.enrollAndUnlock("laptop-1", fake)
            val loops = { harness.viewModel.viewModelScope.coroutineContext.job.children.count { it.isActive } }
            withContext(Dispatchers.Default) { }
            val before = loops()

            harness.keyHolder.unlock(ByteArray(33) { 9 })
            withContext(Dispatchers.Default) { }

            assertEquals(before, loops())
        }

    @Test
    fun `a poll-based connectivity is not held while unlocked`() =
        runTest(testDispatcher) {
            var resumes = 0
            val fake = FakeJmapServer()
            val harness =
                harness(
                    backgroundScope,
                    fake,
                    outboxResume = OutboxResume { resumes++ },
                    connectivity = FakeConnectivity(eventDriven = false),
                )
            harness.enrollAndUnlock("laptop-1", fake)

            harness.connectivity.flap()
            withContext(Dispatchers.Default) { }

            assertEquals(0, resumes)
        }

    @Test
    fun `a drain that never finishes does not stop the next one`() =
        runTest(testDispatcher) {
            var calls = 0
            val second = CompletableDeferred<Unit>()
            val fake = FakeJmapServer()
            val harness =
                harness(
                    backgroundScope,
                    fake,
                    outboxResume =
                        OutboxResume {
                            if (calls++ == 0) CompletableDeferred<Unit>().await() else second.complete(Unit)
                        },
                )
            harness.enrollAndUnlock("laptop-1", fake)

            harness.connectivity.flap()
            harness.connectivity.flap()

            second.await()
        }
}
