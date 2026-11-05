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
        val url = baseUrl() ?: return true
        return withContext(Dispatchers.IO) {
            try {
                val uri = URI.create(url)
                val port =
                    when {
                        uri.port != -1 -> uri.port
                        uri.scheme == "https" -> HTTPS_PORT
                        else -> HTTP_PORT
                    }
                Socket().use { it.connect(InetSocketAddress(uri.host ?: return@withContext false, port), CONNECT_TIMEOUT_MILLIS) }
                true
            } catch (_: IOException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}
