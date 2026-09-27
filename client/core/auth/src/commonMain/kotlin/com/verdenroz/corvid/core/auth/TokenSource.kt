package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.AuthLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import org.publicvalue.multiplatform.oidc.types.remote.ErrorResponse
import org.publicvalue.multiplatform.oidc.util.accessTokenExpired

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(val bearerToken: String) : AuthState
}

/**
 * Holds tokens in memory only (decision 5): nothing here ever touches disk.
 * [adopt] pairs tokens with the exact [OpenIdConnectClient] that issued them,
 * since discovery is per server address and only known once signed in.
 * Observes the [AuthLatch] so the first 401 from any endpoint signs out
 * exactly the way an invalid_grant refresh does.
 */
@OptIn(ExperimentalOpenIdConnect::class)
class TokenSource(
    private val authLatch: AuthLatch,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state

    private var client: OpenIdConnectClient? = null
    private var tokens: AccessTokenResponse? = null

    init {
        // Unconfined so a trip wipes the token in the same call, not on the
        // scope dispatcher's next turn: a reader racing the trip must never
        // observe a token that decision 5 already says is gone.
        scope.launch(Dispatchers.Unconfined) {
            authLatch.tripped.collect { tripped -> if (tripped) signOut() }
        }
    }

    fun adopt(
        client: OpenIdConnectClient,
        tokens: AccessTokenResponse,
    ) {
        this.client = client
        this.tokens = tokens
        _state.value = AuthState.SignedIn(tokens.access_token)
    }

    fun signOut() {
        client = null
        tokens = null
        _state.value = AuthState.SignedOut
    }

    /** Never returns an expired token: it refreshes first, once, or gives up. */
    suspend fun currentBearerToken(): String? {
        val current = tokens ?: return null
        if (!current.accessTokenExpired()) return current.access_token
        return refresh(current)
    }

    private suspend fun refresh(current: AccessTokenResponse): String? {
        val activeClient = client ?: return signOutAndReturnNull()
        val refreshToken = current.refresh_token ?: return signOutAndReturnNull()
        return try {
            val refreshed = activeClient.refreshToken(refreshToken)
            adopt(activeClient, refreshed)
            refreshed.access_token
        } catch (e: OpenIdConnectException.UnsuccessfulTokenRequest) {
            if (e.errorResponse?.error == ErrorResponse.Error.invalid_grant) signOut()
            null
        } catch (e: OpenIdConnectException) {
            null
        }
    }

    private fun signOutAndReturnNull(): String? {
        signOut()
        return null
    }
}
