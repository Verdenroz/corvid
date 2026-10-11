package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.jmap.JmapJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject

internal fun body(request: HttpRequestData): JsonObject =
    JmapJson.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()) as JsonObject

internal fun json(
    scope: MockRequestHandleScope,
    status: HttpStatusCode,
    body: JsonObject,
): HttpResponseData = scope.respond(body.toString(), status, headersOf(HttpHeaders.ContentType, listOf("application/json")))
