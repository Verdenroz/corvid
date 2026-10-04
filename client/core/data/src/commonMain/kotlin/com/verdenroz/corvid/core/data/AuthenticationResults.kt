package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Seal

internal val WHITESPACE = Regex("\\s+")

/**
 * Reads the receiving server's verdict out of `Authentication-Results`. Only the FIRST header
 * counts: the receiving server prepends its own, and everything below it arrived with the
 * message and may be forged. The authserv-id does not discriminate, because a forger copies it.
 */
fun readSeal(authenticationResults: List<String>): Seal {
    val clauses = authenticationResults.firstOrNull()?.split(';').orEmpty().map { it.trim().split(WHITESPACE).mapNotNull(::property) }
    val dmarc = clauses.firstOrNull { clause -> clause.any { it.first.equals("dmarc", ignoreCase = true) } } ?: return Seal.Unsigned
    val result = dmarc.first { it.first.equals("dmarc", ignoreCase = true) }.second
    val from = dmarc.valueOf("header.from")
    return when {
        result.equals("pass", ignoreCase = true) -> Seal.Signed(from)
        result.equals("fail", ignoreCase = true) -> Seal.Failed(claimed = from, signedBy = clauses.dkimSigner())
        else -> Seal.Unsigned
    }
}

private fun property(token: String): Pair<String, String>? = token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }

private fun List<Pair<String, String>>.valueOf(key: String): String? = firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

private fun List<List<Pair<String, String>>>.dkimSigner(): String? =
    firstOrNull { clause -> clause.any { it.first.equals("dkim", ignoreCase = true) && it.second.equals("pass", ignoreCase = true) } }
        ?.valueOf("header.d")
