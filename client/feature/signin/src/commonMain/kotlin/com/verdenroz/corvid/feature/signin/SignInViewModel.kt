package com.verdenroz.corvid.feature.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.domain.DiscoveryFailure
import com.verdenroz.corvid.core.domain.SignIn
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_malformed
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_no_address
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_not_https
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_unreachable
import com.verdenroz.corvid.feature.signin.resources.signin_discovery_version
import com.verdenroz.corvid.feature.signin.resources.signin_failed
import com.verdenroz.corvid.feature.signin.resources.signin_issuer_not_https
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.publicvalue.multiplatform.oidc.OpenIdConnectException

sealed interface SignInUiState {
    data object Idle : SignInUiState

    data object Discovering : SignInUiState

    data class Discovered(val discovery: Discovery) : SignInUiState

    data class SigningIn(val discovery: Discovery?) : SignInUiState

    data class DiscoveryFailed(val message: UiText) : SignInUiState

    data class Failed(val message: UiText) : SignInUiState
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
) : ViewModel() {
    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val state: StateFlow<SignInUiState> = _state

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

    fun signIn(
        server: String,
        issuer: String,
    ) {
        viewModelScope.launch {
            _state.value = SignInUiState.SigningIn((_state.value as? SignInUiState.Discovered)?.discovery)
            try {
                signInUseCase(ServerAddress(server.trimEnd('/')), issuer)
                _state.value = SignInUiState.Idle
            } catch (e: OpenIdConnectException) {
                _state.value = SignInUiState.Failed(uiText(Res.string.signin_failed, UiText.FromServer(e.message)))
            } catch (e: IllegalArgumentException) {
                _state.value = SignInUiState.Failed(uiText(Res.string.signin_issuer_not_https))
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
