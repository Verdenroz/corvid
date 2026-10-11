package com.verdenroz.corvid.feature.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.domain.DiscoveryFailure
import com.verdenroz.corvid.core.domain.EnrollPasskey
import com.verdenroz.corvid.core.domain.SignIn
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.RememberedAccount
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_malformed
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_no_address
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_not_https
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_unreachable
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_version
import com.verdenroz.corvid.feature.signin.resources.signin_server_not_https
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SignInUiState {
    data object Idle : SignInUiState

    data object Discovering : SignInUiState

    data class Discovered(val discovery: Discovery) : SignInUiState

    data class SigningIn(val discovery: Discovery?) : SignInUiState

    data class DiscoveryFailed(val message: UiText) : SignInUiState

    data class Failed(val message: UiText) : SignInUiState

    /** A code made a master passkey, which signs nobody in: the daily passkey does that. */
    data object MasterEnrolled : SignInUiState
}

val SignInUiState.discovery: Discovery?
    get() =
        when (this) {
            is SignInUiState.Discovered -> discovery
            is SignInUiState.SigningIn -> discovery
            else -> null
        }

class SignInViewModel(
    private val discoverAccount: DiscoverAccount,
    private val signInUseCase: SignIn,
    private val enrollPasskey: EnrollPasskey,
    private val preferences: DevicePreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val state: StateFlow<SignInUiState> = _state

    val remembered: StateFlow<RememberedAccount?> =
        preferences.settings.map { it.account }.stateIn(viewModelScope, SharingStarted.Eagerly, preferences.settings.value.account)

    fun discover(email: String) {
        viewModelScope.launch {
            _state.value = SignInUiState.Discovering
            _state.value =
                try {
                    SignInUiState.Discovered(discoverAccount(email))
                } catch (e: DiscoveryFailure) {
                    SignInUiState.DiscoveryFailed(e.message())
                }
        }
    }

    fun reset() {
        _state.value = SignInUiState.Idle
    }

    /** Signs in to a server entered by address, adding a passkey first when [code] is given. */
    fun signIn(
        server: String,
        code: String = "",
    ) {
        val discovery = (_state.value as? SignInUiState.Discovered)?.discovery
        signIn(RememberedAccount(server.trimEnd('/')), discovery, code)
    }

    fun confirm(
        discovery: Discovery,
        code: String = "",
    ) {
        signIn(RememberedAccount(discovery.server.baseUrl, discovery.email), discovery, code)
    }

    fun signInAs(account: RememberedAccount) {
        signIn(account, discovery = null, code = "")
    }

    private fun signIn(
        account: RememberedAccount,
        discovery: Discovery?,
        code: String,
    ) {
        if (!account.server.startsWith("https://")) {
            _state.value = SignInUiState.Failed(uiText(Res.string.signin_server_not_https))
            return
        }
        viewModelScope.launch {
            _state.value = SignInUiState.SigningIn(discovery)
            val server = ServerAddress(account.server)
            _state.value =
                try {
                    val enrolled = if (code.isBlank()) null else enrollPasskey(server, code.trim())
                    if (enrolled == PasskeyRole.Master) {
                        SignInUiState.MasterEnrolled
                    } else {
                        signInUseCase(server)
                        preferences.rememberAccount(account)
                        SignInUiState.Idle
                    }
                } catch (e: PasskeyException) {
                    SignInUiState.Failed(e.userMessage())
                } catch (e: AuthFailure) {
                    SignInUiState.Failed(e.userMessage())
                } catch (e: JmapException) {
                    SignInUiState.Failed(e.userMessage())
                }
        }
    }
}

private fun DiscoveryFailure.message(): UiText =
    when (this) {
        DiscoveryFailure.NoDomain -> uiText(Res.string.signin_discovery_no_address)
        is DiscoveryFailure.Unreachable -> uiText(Res.string.signin_discovery_unreachable, domain)
        DiscoveryFailure.Malformed -> uiText(Res.string.signin_discovery_malformed)
        DiscoveryFailure.UnsupportedVersion -> uiText(Res.string.signin_discovery_version)
        DiscoveryFailure.NotHttps -> uiText(Res.string.signin_discovery_not_https)
    }
