package com.verdenroz.corvid.core.auth

import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The part of the system libfido2 this app calls. Kotlin names map to the C ones by adding the
 * `fido_` prefix and splitting at capitals (`devOpen` is `fido_dev_open`). A `size_t` is a
 * [NativeLong], which is the same width on the 64-bit Linux and macOS this runs on. A C `bool`
 * comes back as a byte, since its upper bits are not defined.
 */
internal interface Fido2Library : Library {
    fun init(flags: Int)

    fun strerr(code: Int): String?

    fun devInfoNew(count: NativeLong): Pointer?

    fun devInfoManifest(
        list: Pointer,
        capacity: NativeLong,
        found: NativeLongByReference,
    ): Int

    fun devInfoPtr(
        list: Pointer,
        index: NativeLong,
    ): Pointer?

    fun devInfoPath(info: Pointer): String?

    fun devInfoFree(
        list: PointerByReference,
        count: NativeLong,
    )

    fun devNew(): Pointer?

    fun devOpen(
        dev: Pointer,
        path: String,
    ): Int

    fun devClose(dev: Pointer): Int

    fun devCancel(dev: Pointer): Int

    fun devFree(dev: PointerByReference)

    fun devHasPin(dev: Pointer): Byte

    fun devHasUv(dev: Pointer): Byte

    fun credNew(): Pointer?

    fun credFree(cred: PointerByReference)

    fun credSetType(
        cred: Pointer,
        type: Int,
    ): Int

    fun credSetClientdataHash(
        cred: Pointer,
        hash: ByteArray,
        length: NativeLong,
    ): Int

    fun credSetRp(
        cred: Pointer,
        id: String,
        name: String,
    ): Int

    fun credSetUser(
        cred: Pointer,
        id: ByteArray,
        idLength: NativeLong,
        name: String,
        displayName: String,
        icon: String?,
    ): Int

    fun credSetRk(
        cred: Pointer,
        option: Int,
    ): Int

    fun credSetUv(
        cred: Pointer,
        option: Int,
    ): Int

    fun credExclude(
        cred: Pointer,
        id: ByteArray,
        length: NativeLong,
    ): Int

    fun devMakeCred(
        dev: Pointer,
        cred: Pointer,
        pin: String?,
    ): Int

    fun credIdPtr(cred: Pointer): Pointer?

    fun credIdLen(cred: Pointer): NativeLong

    fun credAuthdataRawPtr(cred: Pointer): Pointer?

    fun credAuthdataRawLen(cred: Pointer): NativeLong

    fun assertNew(): Pointer?

    fun assertFree(assertion: PointerByReference)

    fun assertSetRp(
        assertion: Pointer,
        id: String,
    ): Int

    fun assertSetClientdataHash(
        assertion: Pointer,
        hash: ByteArray,
        length: NativeLong,
    ): Int

    fun assertAllowCred(
        assertion: Pointer,
        id: ByteArray,
        length: NativeLong,
    ): Int

    fun assertSetUv(
        assertion: Pointer,
        option: Int,
    ): Int

    fun devGetAssert(
        dev: Pointer,
        assertion: Pointer,
        pin: String?,
    ): Int

    fun assertCount(assertion: Pointer): NativeLong

    fun assertIdPtr(
        assertion: Pointer,
        index: NativeLong,
    ): Pointer?

    fun assertIdLen(
        assertion: Pointer,
        index: NativeLong,
    ): NativeLong

    fun assertAuthdataPtr(
        assertion: Pointer,
        index: NativeLong,
    ): Pointer?

    fun assertAuthdataLen(
        assertion: Pointer,
        index: NativeLong,
    ): NativeLong

    fun assertSigPtr(
        assertion: Pointer,
        index: NativeLong,
    ): Pointer?

    fun assertSigLen(
        assertion: Pointer,
        index: NativeLong,
    ): NativeLong

    fun assertUserIdPtr(
        assertion: Pointer,
        index: NativeLong,
    ): Pointer?

    fun assertUserIdLen(
        assertion: Pointer,
        index: NativeLong,
    ): NativeLong

    companion object {
        /** The system library, or null where it is not installed. */
        fun load(): Fido2Library? =
            try {
                Native.load("fido2", Fido2Library::class.java, OPTIONS)
            } catch (e: UnsatisfiedLinkError) {
                null
            }

        internal val OPTIONS =
            mapOf(
                Library.OPTION_FUNCTION_MAPPER to
                    FunctionMapper { _, method -> "fido_" + method.name.replace(Regex("[A-Z]")) { "_" + it.value.lowercase() } },
                Library.OPTION_STRING_ENCODING to "UTF-8",
            )
    }
}
