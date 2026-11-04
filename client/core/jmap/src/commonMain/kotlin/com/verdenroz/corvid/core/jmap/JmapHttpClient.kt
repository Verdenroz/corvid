package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

val JmapJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * The transport contract every JMAP call relies on: no automatic retries, no
 * redirects, no cookies, and every non-2xx response comes back as a normal
 * [io.ktor.client.statement.HttpResponse] rather than an exception, so
 * [JmapClient] can turn a status code into a [JmapError] itself. There is
 * deliberately no `Auth` plugin: the bearer header is attached per call, so
 * the [AuthLatch] sees every 401 before Ktor would think to retry one.
 */
fun HttpClientConfig<*>.applyJmapDefaults() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
    }
    install(ContentNegotiation) {
        json(JmapJson)
    }
}

fun createJmapHttpClient(engine: HttpClientEngine = singleAttemptEngine()): HttpClient =
    HttpClient(engine) {
        applyJmapDefaults()
    }

internal const val SINGLE_ATTEMPT_HEADER = "X-Corvid-Single-Attempt"

private fun singleAttemptEngine(): HttpClientEngine {
    val pool = ConnectionPool()
    return OkHttp.create {
        config { connectionPool(pool) }
        addInterceptor(singleAttempts(pool))
    }
}

/**
 * OkHttp resends a request on a fresh connection when the old one drops mid-flight, unless its body
 * is one-shot, and a request on a stale pooled connection is never resent that way. A request marked
 * with [SINGLE_ATTEMPT_HEADER] is one the server may already have run and must not be sent twice, so
 * it gets a one-shot body and a connection opened for it; the header never leaves the process.
 */
private fun singleAttempts(pool: ConnectionPool) =
    Interceptor { chain ->
        val request = chain.request()
        if (request.header(SINGLE_ATTEMPT_HEADER) == null) return@Interceptor chain.proceed(request)
        pool.evictAll()
        val marked = request.newBuilder().removeHeader(SINGLE_ATTEMPT_HEADER)
        chain.proceed((request.body?.let { marked.method(request.method, OneShotBody(it)) } ?: marked).build())
    }

private class OneShotBody(private val body: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = body.contentType()

    override fun contentLength(): Long = body.contentLength()

    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)

    override fun isOneShot(): Boolean = true
}
