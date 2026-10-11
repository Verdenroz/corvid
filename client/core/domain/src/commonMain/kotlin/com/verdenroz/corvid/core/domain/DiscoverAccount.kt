package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.model.hostOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class Discovery(val server: ServerAddress, val email: String) {
    val serverHost: String get() = hostOf(server.baseUrl)
}

sealed class DiscoveryFailure : Exception() {
    data object NoDomain : DiscoveryFailure()

    data class Unreachable(val domain: String) : DiscoveryFailure()

    data object Malformed : DiscoveryFailure()

    data object UnsupportedVersion : DiscoveryFailure()

    data object NotHttps : DiscoveryFailure()
}

@Serializable
private class DiscoveryDocument(val version: Int, val server: String)

private val json = Json { ignoreUnknownKeys = true }
private val SUPPORTED_VERSIONS = 1..2

/**
 * Finds a mailbox's server from its address. Version 1 documents also name an identity provider,
 * which passkey sign-in has no use for, so it is not read. The SRV record
 * `_corvid._tcp.<domain>` names the web tier's host and port; without one the document
 * is fetched from the domain itself on 443. A record that exists but leads nowhere is an
 * error rather than a reason to try 443, since the domain said where to look.
 */
class DiscoverAccount(
    private val srvLookup: SrvLookup,
    private val documents: DiscoveryDocumentSource,
) {
    suspend operator fun invoke(email: String): Discovery {
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        if (domain.isEmpty()) throw DiscoveryFailure.NoDomain
        val authority = srvLookup.lookup("_corvid._tcp.$domain")?.let { "${it.host.trimEnd('.')}:${it.port}" } ?: domain
        val body = documents.fetch("https://$authority/.well-known/corvid") ?: throw DiscoveryFailure.Unreachable(domain)
        val document =
            try {
                json.decodeFromString<DiscoveryDocument>(body)
            } catch (_: SerializationException) {
                throw DiscoveryFailure.Malformed
            }
        if (document.version !in SUPPORTED_VERSIONS) throw DiscoveryFailure.UnsupportedVersion
        if (!document.server.startsWith("https://")) throw DiscoveryFailure.NotHttps
        return Discovery(ServerAddress(document.server.trimEnd('/')), email.trim())
    }
}
