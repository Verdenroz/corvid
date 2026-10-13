package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress

fun interface EnrollPasskey {
    /** Answers whether the new passkey can approve devices. */
    suspend operator fun invoke(
        server: ServerAddress,
        code: String,
    ): Boolean
}

/**
 * Makes a passkey on this device for the one-time [code]. Registering signs in, so the tokens go to
 * [TokenSource] and the [AuthLatch] is cleared, exactly as a [SignIn] would.
 */
class EnrollPasskeyUseCase(
    private val authApi: AuthApi,
    private val authenticator: Authenticator,
    private val tokenSource: TokenSource,
    private val authLatch: AuthLatch,
) : EnrollPasskey {
    override suspend operator fun invoke(
        server: ServerAddress,
        code: String,
    ): Boolean {
        val options = authApi.registerOptions(server, code)
        val registered = authApi.registerFinish(server, authenticator.create(server, options))
        tokenSource.adopt(registered.tokens, server)
        authLatch.clear()
        return registered.approves
    }
}
