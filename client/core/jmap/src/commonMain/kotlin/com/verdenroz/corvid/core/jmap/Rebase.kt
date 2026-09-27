package com.verdenroz.corvid.core.jmap

/**
 * Moves an advertised URL onto the host that was actually dialled, keeping its
 * path. Stalwart advertises `apiUrl`/`downloadUrl`/`uploadUrl`/`eventSourceUrl`
 * built from its configured hostname, always `https` and with no port, which
 * is wrong for a caller on loopback or a web tier not on 443.
 *
 * A string operation rather than a URL parse, because `downloadUrl` is a
 * template carrying `{accountId}` and friends, and those braces do not
 * survive a round trip through a parser.
 */
fun rebase(
    base: String,
    advertised: String,
): String {
    val trimmedBase = base.trimEnd('/')
    val schemeEnd = advertised.indexOf("://")
    val path =
        if (schemeEnd >= 0) {
            val afterScheme = schemeEnd + 3
            val slash = advertised.indexOf('/', afterScheme)
            if (slash >= 0) advertised.substring(slash) else "/"
        } else {
            advertised
        }
    return "$trimmedBase$path"
}
