package com.verdenroz.corvid.core.common

import java.security.MessageDigest

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
