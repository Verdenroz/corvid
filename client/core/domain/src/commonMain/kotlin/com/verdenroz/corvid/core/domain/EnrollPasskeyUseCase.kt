package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface EnrollPasskey {
    /**
     * Answers whether the new passkey can approve devices. [onWaiting] runs once if the server does not
     * yet accept the new session, which is how long the first passkey on a server takes to switch it on.
     */
    suspend operator fun invoke(
        server: ServerAddress,
        code: String,
        onWaiting: () -> Unit,
    ): Boolean
}

/**
 * Makes a passkey on this device for the one-time [code]. Registering signs in, so once the mail
 * server accepts the returned tokens they go to [TokenSource] and the [AuthLatch] is cleared, exactly
 * as a [SignIn] would. They are held back until then, since a session adopted early would reach the
 * mailbox, be refused, and sign itself out.
 */
class EnrollPasskeyUseCase(
    private val authApi: AuthApi,
    private val authenticator: Authenticator,
    private val tokenSource: TokenSource,
    private val authLatch: AuthLatch,
    private val interval: Duration = 5.seconds,
    private val attempts: Int = 36,
) : EnrollPasskey {
    override suspend operator fun invoke(
        server: ServerAddress,
        code: String,
        onWaiting: () -> Unit,
    ): Boolean {
        val options = authApi.registerOptions(server, code)
        val registered = authApi.registerFinish(server, authenticator.create(server, options))
        repeat(attempts) { attempt ->
            if (authApi.mailAccepts(server, registered.tokens.accessToken)) {
                tokenSource.adopt(registered.tokens, server)
                authLatch.clear()
                return registered.approves
            }
            if (attempt == 0) onWaiting()
            delay(interval)
        }
        throw AuthFailure.SetupPending
    }
}
