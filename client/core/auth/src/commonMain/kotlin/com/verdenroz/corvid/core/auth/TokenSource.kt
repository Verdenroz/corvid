package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val REFRESH_MARGIN = 60.seconds

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(val bearerToken: String, val server: ServerAddress) : AuthState
}

/**
 * Holds tokens in memory only: nothing here ever touches disk.
 * Observes the [AuthLatch] so the first 401 from any endpoint signs out
 * exactly the way a refresh the server refuses does.
 */
class TokenSource(
    private val authLatch: AuthLatch,
    scope: CoroutineScope,
    private val api: AuthApi,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state

    private val refreshMutex = Mutex()
    private var tokens: AuthTokens? = null

    init {
        // Unconfined so a trip wipes the token in the same call, not on the
        // scope dispatcher's next turn: a reader racing the trip must never
        // observe a token the sign-out already wiped.
        scope.launch(Dispatchers.Unconfined) {
            authLatch.tripped.collect { tripped -> if (tripped) signOut() }
        }
    }

    fun adopt(
        tokens: AuthTokens,
        server: ServerAddress,
    ) {
        this.tokens = tokens
        _state.value = AuthState.SignedIn(tokens.accessToken, server)
    }

    fun signOut() {
        tokens = null
        _state.value = AuthState.SignedOut
    }

    /**
     * Never returns a token expiring within [REFRESH_MARGIN]: it refreshes
     * first, once, or gives up. Callers racing a refresh share one request,
     * since a refresh token is good once. Null means signed out. A refresh that
     * cannot reach the server throws the [JmapException] and keeps the session.
     */
    suspend fun currentBearerToken(): String? {
        val current = tokens ?: return null
        if (!current.expiresSoon()) return current.accessToken
        return refreshMutex.withLock {
            val stillCurrent = tokens ?: return@withLock null
            if (!stillCurrent.expiresSoon()) return@withLock stillCurrent.accessToken
            refresh(stillCurrent)
        }
    }

    private fun AuthTokens.expiresSoon() = expiresAt - clock.now() <= REFRESH_MARGIN

    private suspend fun refresh(current: AuthTokens): String? {
        val activeServer = (_state.value as? AuthState.SignedIn)?.server ?: return signOutAndReturnNull()
        val refreshToken = current.refreshToken ?: return signOutAndReturnNull()
        return when (val refreshed = api.refresh(activeServer, refreshToken)) {
            is Refreshed.Tokens -> {
                tokens = refreshed.tokens
                _state.value = AuthState.SignedIn(refreshed.tokens.accessToken, activeServer)
                refreshed.tokens.accessToken
            }
            Refreshed.InvalidGrant -> signOutAndReturnNull()
        }
    }

    private fun signOutAndReturnNull(): String? {
        signOut()
        return null
    }
}
