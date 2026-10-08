package com.verdenroz.corvid.core.model

import kotlin.jvm.JvmInline

/**
 * The scheme/host/port the client dials, e.g. `https://n1.example.test:4443`.
 * Everything a session advertises gets rebased onto this rather than used as
 * given, since the server can't know what address the caller actually used.
 */
@JvmInline
value class ServerAddress(val baseUrl: String)

/** The hostname of a base URL, without scheme, port or path. */
fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')
