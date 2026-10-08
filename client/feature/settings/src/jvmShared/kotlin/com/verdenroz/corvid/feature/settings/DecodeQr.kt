package com.verdenroz.corvid.feature.settings

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

private val QR_ONLY = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

/** The text of the QR code in a frame's luminance plane, or null when the frame holds none; rows are [rowStride] bytes apart. */
internal fun decodeQr(
    luminance: ByteArray,
    rowStride: Int,
    width: Int,
    height: Int,
): String? =
    try {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), QR_ONLY).text
    } catch (e: ReaderException) {
        null
    }
