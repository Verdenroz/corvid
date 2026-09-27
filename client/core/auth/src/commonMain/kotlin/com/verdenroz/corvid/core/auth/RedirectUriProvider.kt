package com.verdenroz.corvid.core.auth

/** The redirect URI this platform actually listens on: fixed on Android, a bound loopback port on desktop. */
interface RedirectUriProvider {
    fun redirectUri(): String
}
