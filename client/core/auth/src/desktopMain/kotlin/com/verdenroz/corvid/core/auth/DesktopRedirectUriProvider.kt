package com.verdenroz.corvid.core.auth

import java.net.ServerSocket

/**
 * Binds a free loopback port once and keeps it: the authorization URL and the
 * local redirect webserver must agree on the exact same port, and this is the
 * only object both sides read it from.
 */
class DesktopRedirectUriProvider : RedirectUriProvider {
    val port: Int = ServerSocket(0).use { it.localPort }

    override fun redirectUri(): String = desktopRedirectUri(port)
}
