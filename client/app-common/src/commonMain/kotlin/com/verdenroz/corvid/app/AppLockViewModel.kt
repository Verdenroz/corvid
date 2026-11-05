package com.verdenroz.corvid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_self_failed_not_accepted
import com.verdenroz.corvid.appcommon.resources.enroll_self_failed_refused
import com.verdenroz.corvid.appcommon.resources.enroll_self_failed_upstream
import com.verdenroz.corvid.appcommon.resources.unreachable_heartbeat
import com.verdenroz.corvid.appcommon.resources.unreachable_key_unreachable
import com.verdenroz.corvid.appcommon.resources.unreachable_request_failed
import com.verdenroz.corvid.appcommon.resources.unreachable_server
import com.verdenroz.corvid.appcommon.resources.unreachable_session
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.common.Connectivity
import com.verdenroz.corvid.core.data.catchingSignIn
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.Invocation
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.jmap.JmapSessionException
import com.verdenroz.corvid.core.jmap.ReconnectPolicy
import com.verdenroz.corvid.core.keyring.ApproveOutcome
import com.verdenroz.corvid.core.keyring.DeviceApprovalRepository
import com.verdenroz.corvid.core.keyring.DeviceKeyLookup
import com.verdenroz.corvid.core.keyring.DeviceKeyRepository
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.keyring.KeyState
import com.verdenroz.corvid.core.keyring.OpenOutcome
import com.verdenroz.corvid.core.keyring.RequestStatus
import com.verdenroz.corvid.core.model.hostOf
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val HEARTBEAT_INTERVAL = 60.seconds
private val APPROVAL_POLL_INTERVAL = 3.seconds

/**
 * Drives [AppLockState] from [TokenSource], [KeyHolder] and a device key
 * lookup built fresh for each signed-in server. Owns the one
 * [JmapClient] a session gets: constructed once on sign-in, outlived by
 * nothing, declared into [UnlockedScopeHolder] once Unlocked so a scoped
 * repository can reuse it without knowing how it was built.
 */
class AppLockViewModel(
    private val tokenSource: TokenSource,
    private val keyHolder: KeyHolder,
    private val authLatch: AuthLatch,
    private val httpClient: HttpClient,
    private val unlockedScopeHolder: UnlockedScopeHolder,
    private val approveWithKey: ApproveWithKey,
    private val devicePreferences: DevicePreferences,
    private val outboxUnlock: OutboxUnlock,
    private val outboxResume: OutboxResume,
    private val connectivity: Connectivity,
) : ViewModel() {
    private val _state = MutableStateFlow<AppLockState>(AppLockState.SignedOut)
    val state: StateFlow<AppLockState> = _state

    private val reconnectPolicy = ReconnectPolicy()
    private var jmapClient: JmapClient? = null
    private var deviceKeyRepository: DeviceKeyRepository? = null
    private var deviceApprovalRepository: DeviceApprovalRepository? = null
    private var deviceName: String? = null
    private var hadKeyThisSession = false
    private var heartbeatJob: Job? = null

    init {
        viewModelScope.launch { tokenSource.state.collect(::onAuthState) }
        // Unconfined for the same reason TokenSource observes the latch this way: a
        // trip must drop the key in the same call, not on this scope's next turn.
        viewModelScope.launch(Dispatchers.Unconfined) {
            authLatch.tripped.collect { tripped -> if (tripped) keyHolder.drop() }
        }
        viewModelScope.launch { keyHolder.state.collect(::onKeyState) }
        viewModelScope.launch { retryWhenOnline() }
        viewModelScope.launch { drainWhenOnline() }
    }

    private val writingOffline = MutableStateFlow(false)

    /** While a message is being written on the lock screen, a returning network does not retry and move the screen away. */
    fun writingOffline(writing: Boolean) {
        writingOffline.value = writing
    }

    /** What the locked screen can say for certain: whichever of these this session knows. */
    val lockFacts: LockFacts
        get() = LockFacts(serverHost, deviceName, lockedAt)

    private val serverHost: String?
        get() = (tokenSource.state.value as? AuthState.SignedIn)?.server?.baseUrl?.let(::hostOf)

    private var lockedAt: Instant? = null

    fun submitDeviceName(name: String) {
        deviceName = name
        val server = serverHost
        viewModelScope.launch {
            if (server != null) devicePreferences.setDeviceName(server, name)
            resolveKey(name)
        }
    }

    fun unlock() {
        deviceName?.let { name -> viewModelScope.launch { resolveKey(name) } }
    }

    fun lock() = keyHolder.drop()

    fun signOut() = tokenSource.signOut()

    private val accountHosts: AccountHosts?
        get() = serverHost?.let(::AccountHosts)

    /** Throttled by [reconnectPolicy], the same policy an automatic reconnect would use. */
    fun retry() {
        val name = deviceName
        val current = tokenSource.state.value
        viewModelScope.launch {
            delay(reconnectPolicy.nextDelay())
            when {
                jmapClient == null && current is AuthState.SignedIn -> connectToServer(current)
                name != null -> resolveKey(name)
            }
        }
    }

    // One retry per outage: the flow ends after the first return, and a failed retry re-enters Unreachable
    // with a fresh flow that waits for the network to drop and return again.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun retryWhenOnline() {
        _state.map { it is AppLockState.Unreachable }.distinctUntilChanged()
            .flatMapLatest { unreachable ->
                if (unreachable) {
                    connectivity.online.regained().take(1).onEach { writingOffline.first { writing -> !writing } }
                } else {
                    emptyFlow()
                }
            }
            .collect { retry() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun drainWhenOnline() {
        _state.map { it is AppLockState.Unlocked }.distinctUntilChanged()
            .flatMapLatest { unlocked -> if (unlocked) connectivity.online.regained() else emptyFlow() }
            .collect { unlockedScopeHolder.openScope()?.let { outboxResume.resume(it) } }
    }

    private suspend fun onAuthState(authState: AuthState) {
        when (authState) {
            is AuthState.SignedOut -> {
                heartbeatJob?.cancel()
                jmapClient = null
                deviceKeyRepository = null
                deviceApprovalRepository = null
                deviceName = null
                hadKeyThisSession = false
                reconnectPolicy.reset()
                keyHolder.drop()
                // Revoked is a dead end by design: only relaunching the app, not
                // this sign-out, gets a revoked device back to a sign-in screen.
                if (_state.value !is AppLockState.Revoked) _state.value = AppLockState.SignedOut
            }
            is AuthState.SignedIn -> if (jmapClient == null) connectToServer(authState)
        }
    }

    private suspend fun connectToServer(signedIn: AuthState.SignedIn) {
        // needsDeviceName only turns true once the session is confirmed reachable: setting
        // it before that let a fast submitDeviceName race a still-connecting session and
        // silently no-op on a null deviceKeyRepository.
        _state.value = AppLockState.Connecting(needsDeviceName = false)
        try {
            val client =
                JmapClient.connect(httpClient, authLatch, signedIn.server.baseUrl, bearerToken = tokenSource::currentBearerToken)
            jmapClient = client
            deviceKeyRepository = DeviceKeyRepository(client)
            deviceApprovalRepository = DeviceApprovalRepository(client)
            val name = deviceName ?: serverHost?.let { devicePreferences.deviceName(it) }?.also { deviceName = it }
            if (name != null) resolveKey(name) else _state.value = AppLockState.Connecting(needsDeviceName = true)
        } catch (e: JmapException) {
            _state.value = AppLockState.Unreachable(uiText(Res.string.unreachable_server))
        } catch (e: JmapSessionException) {
            _state.value = AppLockState.Unreachable(uiText(Res.string.unreachable_session))
        }
    }

    private suspend fun resolveKey(name: String) {
        _state.value = AppLockState.Connecting(needsDeviceName = false)
        val repository = deviceKeyRepository ?: return
        when (val lookup = repository.fetch(name)) {
            is DeviceKeyLookup.Found -> {
                hadKeyThisSession = true
                keyHolder.unlock(lookup.key)
            }
            is DeviceKeyLookup.Missing ->
                if (hadKeyThisSession) {
                    keyHolder.drop()
                    tokenSource.signOut()
                    _state.value = AppLockState.Revoked
                } else {
                    askForKey(name)
                }
            is DeviceKeyLookup.Damaged -> _state.value = AppLockState.KeyDamaged
            is DeviceKeyLookup.ApprovalOff -> _state.value = AppLockState.DeviceApprovalOff
            is DeviceKeyLookup.Unreachable -> _state.value = AppLockState.Unreachable(uiText(Res.string.unreachable_key_unreachable))
        }
    }

    /** Asks the server for a request a master can approve; the key arrives only by that approval. */
    private suspend fun askForKey(name: String) {
        val repository = deviceApprovalRepository ?: return
        _state.value = AppLockState.NeedsEnrollment(name, EnrollmentRequest.Opening)
        try {
            _state.value =
                when (val outcome = repository.open(name)) {
                    is OpenOutcome.Opened -> AppLockState.NeedsEnrollment(name, EnrollmentRequest.Open(outcome.request))
                    is OpenOutcome.TooManyOpen -> AppLockState.NeedsEnrollment(name, EnrollmentRequest.TooManyOpen)
                    is OpenOutcome.NotServed -> AppLockState.DeviceApprovalOff
                }
        } catch (e: JmapException) {
            _state.value = AppLockState.Unreachable(uiText(Res.string.unreachable_request_failed))
        }
    }

    /** Asks only once the last request has ended, so repeated taps open one request. */
    fun askAgain() {
        val name = deviceName ?: return
        val current = _state.value
        val ended =
            current is AppLockState.DeviceApprovalOff ||
                (current as? AppLockState.NeedsEnrollment)?.request.let {
                    it is EnrollmentRequest.Expired || it is EnrollmentRequest.TooManyOpen
                }
        if (!ended) return
        _state.value = AppLockState.NeedsEnrollment(name, EnrollmentRequest.Opening)
        viewModelScope.launch { askForKey(name) }
    }

    /**
     * Approves this device's own open request with a fresh admin passkey, so a first device needs no
     * other. The token goes to this one request and is not stored; the poll picks up the approval.
     */
    fun approveWithAdminPasskey() {
        val (waiting, open) = openEnrollment() ?: return
        val repository = deviceApprovalRepository ?: return
        if (open.selfApproval == SelfApproval.Approving) return
        _state.value = waiting.copy(request = open.copy(selfApproval = SelfApproval.Approving))
        viewModelScope.launch {
            val ended =
                catchingSignIn({ open.failed(it) }) {
                    approveWithKey.withAdminToken { token ->
                        when (repository.approve(open.request.id, open.request.code, token)) {
                            ApproveOutcome.Approved -> null
                            ApproveOutcome.Gone -> EnrollmentRequest.Expired
                            ApproveOutcome.NotAccepted -> open.failed(uiText(Res.string.enroll_self_failed_not_accepted))
                            ApproveOutcome.Refused -> open.failed(uiText(Res.string.enroll_self_failed_refused))
                            ApproveOutcome.UpstreamFailed -> open.failed(uiText(Res.string.enroll_self_failed_upstream))
                        }
                    }
                } ?: return@launch
            val (current, still) = openEnrollment() ?: return@launch
            if (still.request == open.request) _state.value = current.copy(request = ended)
        }
    }

    private fun EnrollmentRequest.Open.failed(message: UiText) = copy(selfApproval = SelfApproval.Failed(message))

    private fun openEnrollment(): Pair<AppLockState.NeedsEnrollment, EnrollmentRequest.Open>? {
        val waiting = _state.value as? AppLockState.NeedsEnrollment ?: return null
        val open = waiting.request as? EnrollmentRequest.Open ?: return null
        return waiting to open
    }

    /**
     * Polls the open request while the caller stays in it, so a screen that is not visible stops asking.
     * An approval reads the key on [viewModelScope], which outlives that caller.
     */
    suspend fun watchRequest() {
        val repository = deviceApprovalRepository ?: return
        while (true) {
            val (waiting, open) = openEnrollment() ?: return
            delay(APPROVAL_POLL_INTERVAL)
            if (openEnrollment()?.second?.request != open.request) continue
            val status =
                try {
                    repository.status(open.request.id)
                } catch (e: JmapException) {
                    continue
                }
            when (status) {
                RequestStatus.Pending -> Unit
                RequestStatus.Approved -> {
                    viewModelScope.launch { resolveKey(waiting.deviceName) }
                    return
                }
                RequestStatus.Expired -> {
                    _state.value = waiting.copy(request = EnrollmentRequest.Expired)
                    return
                }
                RequestStatus.Off -> {
                    _state.value = AppLockState.DeviceApprovalOff
                    return
                }
            }
        }
    }

    private suspend fun onKeyState(keyState: KeyState) {
        when (keyState) {
            is KeyState.Unlocked -> {
                // A sign-out can land between the key unlocking and this collector
                // running, clearing jmapClient underneath it.
                val client = jmapClient
                val hosts = accountHosts
                if (client == null || hosts == null) {
                    _state.value = AppLockState.SignedOut
                    return
                }
                reconnectPolicy.reset()
                val scope = unlockedScopeHolder.open().also { it.declare(client) }
                outboxUnlock.open(scope, keyState.key, hosts.server)
                // A sign-out or a drop can land while the outbox opens; whichever it was has already set the state.
                val current = accountHosts
                if (jmapClient == null || current == null || keyHolder.state.value !is KeyState.Unlocked) return
                _state.value = AppLockState.Unlocked(current)
                heartbeatJob = viewModelScope.launch { heartbeatLoop() }
            }
            is KeyState.Locked -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                unlockedScopeHolder.close()
                if (_state.value is AppLockState.Unlocked) {
                    lockedAt = Clock.System.now()
                    _state.value = AppLockState.Locked
                }
            }
        }
    }

    private suspend fun heartbeatLoop() {
        val client = jmapClient ?: return
        while (true) {
            delay(HEARTBEAT_INTERVAL)
            try {
                client.call(listOf(Invocation("Core/echo", JsonObject(emptyMap()), "hb")))
            } catch (e: JmapException) {
                dropForUnreachableHeartbeat()
                return
            } catch (e: JmapSessionException) {
                dropForUnreachableHeartbeat()
                return
            }
        }
    }

    // Set before drop(): onKeyState's Locked branch only overwrites an Unlocked
    // state, so this keeps the pane's "unreachable" message rather than
    // reading as though the user tapped Lock.
    private fun dropForUnreachableHeartbeat() {
        _state.value = AppLockState.Unreachable(uiText(Res.string.unreachable_heartbeat))
        keyHolder.drop()
    }
}

/** Each time the network comes back after a drop; the value current when collection starts is not a return. */
private fun StateFlow<Boolean>.regained(): Flow<Boolean> = drop(1).filter { it }

/** The signed-in server's hostname. */
data class AccountHosts(
    val server: String,
) {
    /** The first label of [server], the key pill's name for the machine holding the key. */
    val short: String get() = shortHost(server)
}

data class LockFacts(
    val server: String?,
    val device: String?,
    val lockedAt: Instant?,
)
