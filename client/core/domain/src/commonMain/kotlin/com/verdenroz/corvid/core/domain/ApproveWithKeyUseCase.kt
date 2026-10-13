package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.AuthState
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.Purpose
import com.verdenroz.corvid.core.auth.TokenSource

interface ApproveWithKey {
    suspend fun <T> withAdminToken(use: suspend (String) -> T): T
}

/**
 * A fresh assertion by a passkey that approves devices at the server this session signed in to. The token is lent to
 * the caller's block and never handed to [TokenSource], so it is spent once and dropped.
 */
class ApproveWithKeyUseCase(
    private val authApi: AuthApi,
    private val authenticator: Authenticator,
    private val tokenSource: TokenSource,
) : ApproveWithKey {
    override suspend fun <T> withAdminToken(use: suspend (String) -> T): T {
        val server = checkNotNull((tokenSource.state.value as? AuthState.SignedIn)?.server) { "not signed in" }
        val options = authApi.loginOptions(server, Purpose.Approve)
        return use(authApi.loginFinish(server, authenticator.get(server, options)).accessToken)
    }
}
