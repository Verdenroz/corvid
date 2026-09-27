package com.verdenroz.corvid.app

import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.keyring.KeyState
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.FakeFileNode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

private class Harness(scope: CoroutineScope, fake: FakeJmapServer) {
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
        )

    fun signIn() = tokenSource.adopt(FakeOidcClient { freshToken() }, freshToken(), ServerAddress(BASE_URL))
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
    ) = Harness(scope, fake).also { harness = it }

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
    fun `naming a new device enrolls it and unlocks`() =
        runTest(testDispatcher) {
            val harness = harness(backgroundScope, FakeJmapServer())
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }

            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }

            assertIs<KeyState.Unlocked>(harness.keyHolder.state.value)
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

            assertEquals("the device key could not be reached", unreachable.reason)
        }

    @Test
    fun `a damaged key file reads as unreachable, not revoked, and nothing is wiped`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            val fileNode = fake.fileNodes.first { it.name == "laptop-1.key" }
            fake.blobs[fileNode.blobId!!] = ByteArray(10)
            harness.viewModel.unlock()
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals("the device key is damaged", unreachable.reason)
            assertEquals(1, fake.fileNodes.count { it.name == "laptop-1.key" })
        }

    @Test
    fun `an ambiguous device folder reads as unreachable, not revoked`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            fake.fileNodes += FakeFileNode("d1", null, "corvid-devices", "directory")
            fake.fileNodes += FakeFileNode("d2", null, "corvid-devices", "directory")

            harness.viewModel.submitDeviceName("laptop-1")
            val unreachable = assertIs<AppLockState.Unreachable>(harness.viewModel.state.awaitState { it is AppLockState.Unreachable })

            assertEquals("more than one device folder exists", unreachable.reason)
        }

    @Test
    fun `a key missing after this session already enrolled one reads as revoked`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }
            harness.keyHolder.drop()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            fake.fileNodes.removeAll { it.name == "laptop-1.key" }
            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.Revoked }
        }

    @Test
    fun `the lock button drops the key and a 401 anywhere does the same`() =
        runTest(testDispatcher) {
            val harness = harness(backgroundScope, FakeJmapServer())
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }

            harness.viewModel.lock()
            harness.viewModel.state.awaitState { it is AppLockState.Locked }

            harness.viewModel.unlock()
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }

            harness.authLatch.trip()
            harness.keyHolder.state.first { it is KeyState.Locked }
        }

    @Test
    fun `a heartbeat failure drops the key like any other unreachable call`() =
        runTest(testDispatcher) {
            val fake = FakeJmapServer()
            val harness = harness(backgroundScope, fake)
            harness.signIn()
            harness.viewModel.state.awaitState { it is AppLockState.Connecting && it.needsDeviceName }
            harness.viewModel.submitDeviceName("laptop-1")
            harness.viewModel.state.awaitState { it is AppLockState.Unlocked }

            fake.unreachable = true
            advanceTimeBy(61.seconds)
            harness.viewModel.state.awaitState { it is AppLockState.Locked }
        }
}
