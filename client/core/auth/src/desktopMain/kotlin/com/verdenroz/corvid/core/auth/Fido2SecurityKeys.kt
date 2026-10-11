package com.verdenroz.corvid.core.auth

import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

private const val COSE_ES256 = -7
private const val OPTION_FALSE = 1
private const val OPTION_TRUE = 2
private const val MAX_KEYS = 8L

/** libfido2's answers by number, from `fido/err.h`. */
internal fun securityKeyError(code: Int): SecurityKeyError =
    when (code) {
        0x31 -> SecurityKeyError.PinInvalid
        0x35, 0x36 -> SecurityKeyError.PinNotSet
        0x32 -> SecurityKeyError.PinBlocked
        0x34, 0x3c -> SecurityKeyError.PinAuthBlocked
        0x2e -> SecurityKeyError.NoCredentials
        0x19 -> SecurityKeyError.CredentialExcluded
        0x2f, 0x3a -> SecurityKeyError.NotTouched
        0x2d, 0x27 -> SecurityKeyError.Cancelled
        0x26, 0x2b -> SecurityKeyError.Unsupported
        else -> SecurityKeyError.Other
    }

internal class Fido2SecurityKeys(private val lib: Fido2Library) : SecurityKeys {
    init {
        lib.init(0)
    }

    override fun first(): SecurityKey? {
        val list = lib.devInfoNew(NativeLong(MAX_KEYS)) ?: return null
        try {
            val found = NativeLongByReference()
            if (lib.devInfoManifest(list, NativeLong(MAX_KEYS), found) != 0 || found.value.toLong() == 0L) return null
            val path = lib.devInfoPath(lib.devInfoPtr(list, NativeLong(0)) ?: return null) ?: return null
            return Fido2SecurityKey.open(lib, path)
        } finally {
            lib.devInfoFree(PointerByReference(list), NativeLong(MAX_KEYS))
        }
    }
}

internal class Fido2SecurityKey private constructor(
    private val lib: Fido2Library,
    private val dev: Pointer,
) : SecurityKey {
    override val verification: Verification
        get() =
            when {
                lib.devHasUv(dev) != 0.toByte() -> Verification.BuiltIn
                lib.devHasPin(dev) != 0.toByte() -> Verification.Pin
                else -> Verification.None
            }

    override fun make(
        creation: Creation,
        pin: String?,
    ): Made {
        val cred = checkNotNull(lib.credNew()) { "libfido2 could not allocate a credential" }
        try {
            checked(lib.credSetType(cred, COSE_ES256))
            checked(lib.credSetClientdataHash(cred, creation.clientDataHash, size(creation.clientDataHash)))
            checked(lib.credSetRp(cred, creation.rpId, creation.rpName))
            checked(lib.credSetUser(cred, creation.userId, size(creation.userId), creation.userName, creation.displayName, null))
            checked(lib.credSetRk(cred, if (creation.discoverable) OPTION_TRUE else OPTION_FALSE))
            checked(lib.credSetUv(cred, OPTION_TRUE))
            creation.exclude.forEach { checked(lib.credExclude(cred, it, size(it))) }
            checked(lib.devMakeCred(dev, cred, pin))
            return Made(
                bytes(lib.credIdPtr(cred), lib.credIdLen(cred)),
                bytes(lib.credAuthdataRawPtr(cred), lib.credAuthdataRawLen(cred)),
            )
        } finally {
            lib.credFree(PointerByReference(cred))
        }
    }

    override fun assert(
        request: Request,
        pin: String?,
    ): Asserted {
        val assertion = checkNotNull(lib.assertNew()) { "libfido2 could not allocate an assertion" }
        try {
            checked(lib.assertSetRp(assertion, request.rpId))
            checked(lib.assertSetClientdataHash(assertion, request.clientDataHash, size(request.clientDataHash)))
            request.allow.forEach { checked(lib.assertAllowCred(assertion, it, size(it))) }
            checked(lib.assertSetUv(assertion, OPTION_TRUE))
            checked(lib.devGetAssert(dev, assertion, pin))
            val first = NativeLong(0)
            if (lib.assertCount(assertion).toLong() == 0L) throw SecurityKeyException(SecurityKeyError.NoCredentials)
            return Asserted(
                bytes(lib.assertIdPtr(assertion, first), lib.assertIdLen(assertion, first)),
                bytes(lib.assertAuthdataRawPtr(assertion, first), lib.assertAuthdataRawLen(assertion, first)),
                bytes(lib.assertSigPtr(assertion, first), lib.assertSigLen(assertion, first)),
                bytes(lib.assertUserIdPtr(assertion, first), lib.assertUserIdLen(assertion, first)).takeIf { it.isNotEmpty() },
            )
        } finally {
            lib.assertFree(PointerByReference(assertion))
        }
    }

    override fun cancel() {
        lib.devCancel(dev)
    }

    override fun close() {
        lib.devClose(dev)
        lib.devFree(PointerByReference(dev))
    }

    private fun checked(code: Int) {
        if (code != 0) throw SecurityKeyException(securityKeyError(code), lib.strerr(code))
    }

    private fun size(bytes: ByteArray) = NativeLong(bytes.size.toLong())

    private fun bytes(
        pointer: Pointer?,
        length: NativeLong,
    ): ByteArray = pointer?.getByteArray(0, length.toInt()) ?: ByteArray(0)

    companion object {
        fun open(
            lib: Fido2Library,
            path: String,
        ): SecurityKey? {
            val dev = lib.devNew() ?: return null
            val code = lib.devOpen(dev, path)
            if (code != 0) {
                lib.devFree(PointerByReference(dev))
                return null
            }
            return Fido2SecurityKey(lib, dev)
        }
    }
}
