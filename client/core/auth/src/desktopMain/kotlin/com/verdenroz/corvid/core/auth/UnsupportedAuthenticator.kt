package com.verdenroz.corvid.core.auth

/** A desktop with no way to ask for a passkey yet: every prompt is unsupported. */
object UnsupportedAuthenticator : Authenticator {
    override suspend fun create(options: String): String = throw PasskeyException.Unsupported

    override suspend fun get(options: String): String = throw PasskeyException.Unsupported
}
