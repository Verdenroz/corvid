package com.verdenroz.corvid.core.jmap

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

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

fun createJmapHttpClient(engine: HttpClientEngine = OkHttp.create()): HttpClient =
    HttpClient(engine) {
        applyJmapDefaults()
    }
