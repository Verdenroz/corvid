package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.model.ServerAddress

fun interface EnrollPasskey {
    /** Answers the role the code enrolled. */
    suspend operator fun invoke(
        server: ServerAddress,
        code: String,
    ): PasskeyRole
}

/** Makes a passkey on this device for the one-time [code]. It signs nobody in; that takes a [SignIn]. */
class EnrollPasskeyUseCase(
    private val authApi: AuthApi,
    private val authenticator: Authenticator,
) : EnrollPasskey {
    override suspend operator fun invoke(
        server: ServerAddress,
        code: String,
    ): PasskeyRole {
        val options = authApi.registerOptions(server, code)
        return authApi.registerFinish(server, authenticator.create(server, options))
    }
}
