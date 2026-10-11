package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress

fun interface SignIn {
    suspend operator fun invoke(server: ServerAddress)
}

/**
 * Signs in with a passkey: the server's options go to the platform prompt, and the assertion it
 * returns goes back for tokens, which [TokenSource] holds and nothing writes to disk. Clears the
 * [AuthLatch] on success, since a stale trip from before this sign-in would otherwise short-circuit
 * every call forever.
 */
class SignInUseCase(
    private val authApi: AuthApi,
    private val authenticator: Authenticator,
    private val tokenSource: TokenSource,
    private val authLatch: AuthLatch,
) : SignIn {
    override suspend operator fun invoke(server: ServerAddress) {
        val options = authApi.loginOptions(server, PasskeyRole.Daily)
        val tokens = authApi.loginFinish(server, authenticator.get(options))
        tokenSource.adopt(tokens, server)
        authLatch.clear()
    }
}
