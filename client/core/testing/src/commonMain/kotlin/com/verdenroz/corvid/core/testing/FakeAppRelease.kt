package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.common.sha256Hex
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/** The files under `/corvid/app`, the builds the server carries; a name nothing was published under answers 404. */
class FakeAppRelease {
    private val files = mutableMapOf<String, ByteArray>()

    /** The Authorization header of each request, null where there was none. */
    val authorizations: MutableList<String?> = CopyOnWriteArrayList()

    /** The file names requested, in order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** Answers every request with a 401 carrying a challenge, as the forward-auth gate does for a rejected token. */
    var refuse: Boolean = false

    /** Serves [bytes] under [name], binary as is. */
    fun publish(
        name: String,
        bytes: ByteArray,
    ) {
        files[name] = bytes
    }

    fun unpublish(name: String) {
        files.remove(name)
    }

    /**
     * Serves a manifest for [versionCode] naming the builds given as platform to bytes. A digest or size can be overridden
     * to describe a file other than the one served.
     */
    fun release(
        versionCode: Int,
        android: ByteArray? = null,
        linux: ByteArray? = null,
        sha256: (ByteArray) -> String = ::sha256Hex,
        size: (ByteArray) -> Long = { it.size.toLong() },
        version: Int = 1,
    ) {
        val builds = mapOf("android" to ("corvid.apk" to android), "linux" to ("corvid-linux-x64.tar.gz" to linux))
        builds.values.forEach { (name, bytes) -> if (bytes != null) publish(name, bytes) }
        val manifest =
            buildJsonObject {
                put("version", version)
                put("versionCode", versionCode)
                put("versionName", "0.1.$versionCode")
                builds.forEach { (platform, build) ->
                    val (name, bytes) = build
                    if (bytes != null) {
                        put(
                            platform,
                            buildJsonObject {
                                put("path", name)
                                put("sha256", sha256(bytes))
                                put("size", size(bytes))
                            },
                        )
                    }
                }
            }
        publish("manifest.json", manifest.toString().encodeToByteArray())
    }

    internal fun handle(
        scope: MockRequestHandleScope,
        request: HttpRequestData,
    ): HttpResponseData {
        authorizations += request.headers[HttpHeaders.Authorization]
        val name = request.url.encodedPath.removePrefix("/corvid/app/")
        requests += name
        if (refuse) return scope.respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, listOf("Bearer")))
        val bytes = files[name] ?: return scope.respond("not found", HttpStatusCode.NotFound)
        val type = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
        return scope.respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, listOf(type)))
    }
}
