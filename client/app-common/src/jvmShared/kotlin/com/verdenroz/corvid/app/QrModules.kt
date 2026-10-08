package com.verdenroz.corvid.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual fun qrModules(text: String): QrModules {
    // A requested size below the natural module count makes ZXing return one pixel per module.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return QrModules(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix[it % matrix.width, it / matrix.width] })
}
