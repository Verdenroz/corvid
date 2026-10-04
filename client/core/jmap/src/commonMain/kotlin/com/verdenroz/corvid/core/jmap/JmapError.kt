package com.verdenroz.corvid.core.jmap

/**
 * Every failure a JMAP call can end in. Retry policy lives with the caller,
 * not here: [ReconnectPolicy] backs off on [Unreachable] and [Server], never
 * on [Unauthorized], [Forbidden], [TlsFailure] or [Request].
 */
sealed interface JmapError {
    /** Terminal: the [AuthLatch] is tripped and tokens are wiped. */
    data object Unauthorized : JmapError

    /** Terminal: the account cannot do this regardless of the token. */
    data object Forbidden : JmapError

    /** DNS, connect or timeout. Worth another attempt once the network is back. */
    data class Unreachable(val cause: Throwable) : JmapError

    /** Never retried: a cert failure means the endpoint cannot be trusted, not that it's down. */
    data class TlsFailure(val cause: Throwable) : JmapError

    /** 429. [retryAfterSeconds] comes from the server's own `Retry-After`. */
    data class RateLimited(val retryAfterSeconds: Long?) : JmapError

    /** 5xx. Worth another attempt. */
    data class Server(val status: Int) : JmapError

    /** An RFC 7807 problem body. Never retried: the request itself was rejected. */
    data class Request(val type: String, val detail: String?) : JmapError

    /** A JMAP method error inside an otherwise-successful response. The caller decides what it means. */
    data class Method(val type: String, val description: String?) : JmapError
}

class JmapException(val error: JmapError) : Exception()

fun JmapException.userMessage(): String = "could not reach the server"

/** The session response itself is unusable: not an outcome [JmapError] models, since no retry fixes it. */
class JmapSessionException(message: String) : Exception(message)
