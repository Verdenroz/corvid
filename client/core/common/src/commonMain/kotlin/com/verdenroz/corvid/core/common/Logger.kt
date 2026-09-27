package com.verdenroz.corvid.core.common

/**
 * Structured logging only: callers pass a tag and a fixed message, never
 * interpolated mail content (subjects, senders, bodies). The lock gate and
 * JMAP layers must not leak what they are looking at into logs.
 */
expect object Logger {
    fun d(
        tag: String,
        message: String,
    )

    fun i(
        tag: String,
        message: String,
    )

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}
