package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import org.publicvalue.multiplatform.oidc.types.remote.ErrorResponse
import org.publicvalue.multiplatform.oidc.util.accessTokenExpired
import kotlin.time.Duration.Companion.seconds

private val REFRESH_MARGIN = 60.seconds

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(val bearerToken: String, val server: ServerAddress) : AuthState
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

    private val refreshMutex = Mutex()
    private var client: OpenIdConnectClient? = null
    private var tokens: AccessTokenResponse? = null
    private var server: ServerAddress? = null

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
        server: ServerAddress,
    ) {
        this.client = client
        this.tokens = tokens
        this.server = server
        _state.value = AuthState.SignedIn(tokens.access_token, server)
    }

    fun signOut() {
        client = null
        tokens = null
        server = null
        _state.value = AuthState.SignedOut
    }

    /**
     * Never returns a token expiring within [REFRESH_MARGIN]: it refreshes
     * first, once, or gives up. Two callers racing a refresh share one
     * request, since a rotated refresh token would fail the second one.
     */
    suspend fun currentBearerToken(): String? {
        val current = tokens ?: return null
        if (!current.accessTokenExpired(REFRESH_MARGIN)) return current.access_token
        return refreshMutex.withLock {
            val stillCurrent = tokens ?: return@withLock null
            if (!stillCurrent.accessTokenExpired(REFRESH_MARGIN)) return@withLock stillCurrent.access_token
            refresh(stillCurrent)
        }
    }

    private suspend fun refresh(current: AccessTokenResponse): String? {
        val activeClient = client ?: return signOutAndReturnNull()
        val activeServer = server ?: return signOutAndReturnNull()
        val refreshToken = current.refresh_token ?: return signOutAndReturnNull()
        return try {
            val refreshed = activeClient.refreshToken(refreshToken)
            this.tokens = refreshed
            _state.value = AuthState.SignedIn(refreshed.access_token, activeServer)
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
