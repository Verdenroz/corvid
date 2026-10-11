package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.auth.AuthApi
import com.verdenroz.corvid.core.auth.AuthTokens
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.auth.Refreshed
import com.verdenroz.corvid.core.model.ServerAddress
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A clock a test moves by hand. */
class FakeClock(var now: Instant = Instant.parse("2030-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = now

    operator fun plusAssign(duration: Duration) {
        now += duration
    }
}

fun authTokens(
    accessToken: String = "at1",
    refreshToken: String? = "rt1",
    expiresIn: Duration = 1.hours,
    clock: Clock = FakeClock(),
) = AuthTokens(accessToken, refreshToken, clock.now() + expiresIn)

/**
 * The sentinel's public passkey routes with every answer a test can set. Each call is recorded as
 * its name and argument. Options and credentials are opaque strings, as the real ones are to the app.
 */
class FakeAuthApi(var tokens: AuthTokens = authTokens()) : AuthApi {
    val calls: MutableList<String> = mutableListOf()
    var registers: PasskeyRole = PasskeyRole.Daily
    var optionsFailure: Exception? = null
    var finishFailure: Exception? = null
    var refresh: suspend (String) -> Refreshed = { Refreshed.Tokens(tokens) }

    override suspend fun registerOptions(
        server: ServerAddress,
        code: String,
    ): String {
        calls += "registerOptions $code"
        optionsFailure?.let { throw it }
        return "creation-options:$code"
    }

    override suspend fun registerFinish(
        server: ServerAddress,
        credential: String,
    ): PasskeyRole {
        calls += "registerFinish $credential"
        finishFailure?.let { throw it }
        return registers
    }

    override suspend fun loginOptions(
        server: ServerAddress,
        role: PasskeyRole,
    ): String {
        calls += "loginOptions ${role.wire}"
        optionsFailure?.let { throw it }
        return "request-options:${role.wire}"
    }

    override suspend fun loginFinish(
        server: ServerAddress,
        credential: String,
    ): AuthTokens {
        calls += "loginFinish $credential"
        finishFailure?.let { throw it }
        return tokens
    }

    override suspend fun refresh(
        server: ServerAddress,
        refreshToken: String,
    ): Refreshed {
        calls += "refresh $refreshToken"
        return refresh(refreshToken)
    }
}

/** The platform prompt with no platform: answers a credential that names its options, or the [failure] set. */
class FakeAuthenticator : Authenticator {
    val calls: MutableList<String> = mutableListOf()
    var failure: Exception? = null

    override suspend fun create(options: String): String {
        calls += "create $options"
        failure?.let { throw it }
        return "created:$options"
    }

    override suspend fun get(options: String): String {
        calls += "get $options"
        failure?.let { throw it }
        return "asserted:$options"
    }
}
