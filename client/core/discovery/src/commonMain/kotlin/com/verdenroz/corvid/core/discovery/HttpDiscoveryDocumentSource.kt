package com.verdenroz.corvid.core.discovery

import com.verdenroz.corvid.core.domain.DiscoveryDocumentSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.IOException

class HttpDiscoveryDocumentSource(private val httpClient: HttpClient) : DiscoveryDocumentSource {
    override suspend fun fetch(url: String): String? {
        val response =
            try {
                httpClient.get(url)
            } catch (e: IOException) {
                return null
            }
        return if (response.status == HttpStatusCode.OK) response.bodyAsText() else null
    }
}
