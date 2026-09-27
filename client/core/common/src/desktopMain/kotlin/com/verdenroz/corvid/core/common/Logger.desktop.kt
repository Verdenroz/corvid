package com.verdenroz.corvid.core.common

actual object Logger {
    actual fun d(
        tag: String,
        message: String,
    ) = println("D/$tag: $message")

    actual fun i(
        tag: String,
        message: String,
    ) = println("I/$tag: $message")

    actual fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        println("W/$tag: $message")
        throwable?.printStackTrace()
    }

    actual fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        System.err.println("E/$tag: $message")
        throwable?.printStackTrace()
    }
}
