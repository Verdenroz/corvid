package com.verdenroz.corvid

import android.content.Context
import android.util.Base64
import com.verdenroz.corvid.core.jmap.EventParser
import com.verdenroz.corvid.core.jmap.ReconnectPolicy
import com.verdenroz.corvid.core.prefs.DevicePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val STREAM_KEYS = "stream"
private const val UNAUTHORIZED = 401
private val CONNECT_TIMEOUT_MS = 15.seconds.inWholeMilliseconds

// The server sends a keepalive every 240 s, so a quieter connection is dead.
private val READ_TIMEOUT_MS = 5.minutes.inWholeMilliseconds

private enum class Ended { Dropped, Revoked }

/** The app's own connection to the sentinel: previews decrypted with this device's keys, reconnecting until stopped. */
internal class PushStream(
    context: Context,
    private val preferences: DevicePreferences,
) {
    private val keys = DefaultKeyManager(context)
    private val previews = AndroidMailPreviews(context)

    suspend fun run() {
        val policy = ReconnectPolicy()
        while (coroutineContext.isActive) {
            val settings = preferences.settings.value
            val server = settings.account?.server
            val token = settings.push.streamToken
            if (server == null || token == null) return
            val ended =
                try {
                    connect(server, token, policy)
                } catch (e: IOException) {
                    Ended.Dropped
                }
            if (ended == Ended.Revoked) return preferences.setPushFailed()
            delay(policy.nextDelay())
        }
    }

    private suspend fun connect(
        server: String,
        token: String,
        policy: ReconnectPolicy,
    ): Ended {
        val connection = URL("$server/corvid/push/stream").openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
        connection.readTimeout = READ_TIMEOUT_MS.toInt()
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "text/event-stream")
        preferences.streamCursor()?.let { connection.setRequestProperty("Last-Event-ID", it) }
        return coroutineScope {
            val reading = async(Dispatchers.IO) { read(connection, policy) }
            try {
                reading.await()
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun read(
        connection: HttpURLConnection,
        policy: ReconnectPolicy,
    ): Ended {
        if (connection.responseCode == UNAUTHORIZED) return Ended.Revoked
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return Ended.Dropped
        val parser = EventParser()
        connection.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val event = parser.line(line) ?: continue
                policy.reset()
                if (event.name == "push") show(event.data)
                event.id?.let { preferences.setStreamCursor(it) }
            }
        }
        return Ended.Dropped
    }

    private fun show(data: String) {
        val sealed = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val content =
            try {
                keys.decrypt(STREAM_KEYS, sealed)
            } catch (e: GeneralSecurityException) {
                null
            }
        previews.show(content ?: return, decrypted = true)
    }
}
