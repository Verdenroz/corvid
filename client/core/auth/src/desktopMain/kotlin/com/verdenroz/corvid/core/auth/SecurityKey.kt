package com.verdenroz.corvid.core.auth

/** How a key proves the person is there beyond touching it. */
internal enum class Verification {
    Pin,

    /** A fingerprint or similar on the key itself. */
    BuiltIn,

    /** Neither, so a passkey that must be verified cannot be made or used. */
    None,
}

internal class Creation(
    val rpId: String,
    val rpName: String,
    val userId: ByteArray,
    val userName: String,
    val displayName: String,
    val exclude: List<ByteArray>,
    val discoverable: Boolean,
)

internal class Request(
    val rpId: String,
    val allow: List<ByteArray>,
)

internal class Made(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
)

internal class Asserted(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val userHandle: ByteArray?,
)

internal enum class SecurityKeyError {
    PinInvalid,
    PinNotSet,
    PinBlocked,
    PinAuthBlocked,
    NoCredentials,
    CredentialExcluded,
    NotTouched,
    Cancelled,
    Unsupported,
    Other,
}

internal class SecurityKeyException(val error: SecurityKeyError, message: String? = null) : Exception(message)

/** A security key on the USB or NFC bus. Its calls block until the person touches it. */
internal interface SecurityKey : AutoCloseable {
    val verification: Verification

    fun make(
        creation: Creation,
        clientDataHash: ByteArray,
        pin: String?,
    ): Made

    fun assert(
        request: Request,
        clientDataHash: ByteArray,
        pin: String?,
    ): Asserted

    /** Ends a call blocked on a touch, from another thread. */
    fun cancel()
}

internal fun interface SecurityKeys {
    /** The first key found, or null when none is attached. */
    fun first(): SecurityKey?
}
