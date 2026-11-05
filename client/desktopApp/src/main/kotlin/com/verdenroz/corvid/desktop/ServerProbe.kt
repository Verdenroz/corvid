package com.verdenroz.corvid.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

private const val CONNECT_TIMEOUT_MILLIS = 5_000
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80

/** Whether a TCP connection to the signed-in server opens; true when there is no server to ask. */
class ServerProbe(private val baseUrl: () -> String?) {
    suspend operator fun invoke(): Boolean {
        val uri = baseUrl()?.let(URI::create) ?: return true
        val port =
            when {
                uri.port != -1 -> uri.port
                uri.scheme == "https" -> HTTPS_PORT
                else -> HTTP_PORT
            }
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(uri.host, port), CONNECT_TIMEOUT_MILLIS) }
                true
            } catch (_: IOException) {
                false
            }
        }
    }
}
