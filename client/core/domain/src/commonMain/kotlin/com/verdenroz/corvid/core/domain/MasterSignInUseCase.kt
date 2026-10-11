package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.auth.TokenSource

interface MasterSignIn {
    suspend fun <T> withAdminToken(use: suspend (String) -> T): T
}

/**
 * A fresh master passkey assertion at the server this session signed in to. The token is lent to
 * the caller's block and never handed to [TokenSource], so it is spent once and dropped.
 */
class MasterSignInUseCase(
    private val authApi: AuthApi,
    private val authenticator: Authenticator,
    private val tokenSource: TokenSource,
) : MasterSignIn {
    override suspend fun <T> withAdminToken(use: suspend (String) -> T): T {
        val server = checkNotNull((tokenSource.state.value as? AuthState.SignedIn)?.server) { "not signed in" }
        val options = authApi.loginOptions(server, PasskeyRole.Master)
        return use(authApi.loginFinish(server, authenticator.get(options)).accessToken)
    }
}
