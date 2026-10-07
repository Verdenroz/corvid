package com.verdenroz.corvid.core.domain

data class SrvTarget(val host: String, val port: Int)

fun interface SrvLookup {
    /** The highest-priority target of the SRV record at [name], or null when there is none. */
    suspend fun lookup(name: String): SrvTarget?
}

fun interface DiscoveryDocumentSource {
    /** The body of the 200 response at [url], or null when the host is unreachable or answers anything else. */
    suspend fun fetch(url: String): String?
}
