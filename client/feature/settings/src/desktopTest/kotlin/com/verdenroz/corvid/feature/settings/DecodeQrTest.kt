package com.verdenroz.corvid.feature.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val LINK = "corvid-approve:0123456789abcdef0123456789abcdef?code=042917"
private const val SIDE = 400

/** A camera-like luminance plane: the code drawn dark on light, each row padded to [stride] bytes. */
private fun frame(
    text: String,
    stride: Int,
): ByteArray {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIDE, SIDE)
    return ByteArray(stride * SIDE) { index ->
        val x = index % stride
        val y = index / stride
        if (x < SIDE && matrix[x, y]) 0 else 255.toByte()
    }
}

class DecodeQrTest {
    @Test
    fun `a frame holding a code decodes to its text`() {
        assertEquals(LINK, decodeQr(frame(LINK, stride = SIDE), rowStride = SIDE, width = SIDE, height = SIDE))
    }

    @Test
    fun `row padding beyond the width does not shift the code`() {
        assertEquals(LINK, decodeQr(frame(LINK, stride = SIDE + 48), rowStride = SIDE + 48, width = SIDE, height = SIDE))
    }

    @Test
    fun `a frame without a code decodes to nothing`() {
        assertNull(decodeQr(ByteArray(SIDE * SIDE) { 200.toByte() }, rowStride = SIDE, width = SIDE, height = SIDE))
    }
}
