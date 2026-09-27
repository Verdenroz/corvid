package com.verdenroz.corvid.feature.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.domain.SignInUseCase
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.publicvalue.multiplatform.oidc.OpenIdConnectException

sealed interface SignInUiState {
    data object Idle : SignInUiState

    data object SigningIn : SignInUiState

    data class Failed(val message: String) : SignInUiState
}

class SignInViewModel(private val signInUseCase: SignInUseCase) : ViewModel() {
    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val state: StateFlow<SignInUiState> = _state

    fun signIn(
        server: String,
        issuer: String,
    ) {
        viewModelScope.launch {
            _state.value = SignInUiState.SigningIn
            try {
                signInUseCase(ServerAddress(server.trimEnd('/')), issuer)
                _state.value = SignInUiState.Idle
            } catch (e: OpenIdConnectException) {
                _state.value = SignInUiState.Failed(e.message)
            } catch (e: IllegalArgumentException) {
                _state.value = SignInUiState.Failed(e.message ?: "invalid identity provider")
            }
        }
    }
}
