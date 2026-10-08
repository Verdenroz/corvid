package com.verdenroz.corvid.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor

/** A square grid of QR modules, quiet zone included. */
class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = dark[y * size + x]
}

/** Encodes [text] at error correction level M: the lowest level is hard for a camera to read off a screen. */
expect fun qrModules(text: String): QrModules

/**
 * Draws [text] as a QR code on white, each module a whole number of device pixels wide: a module
 * on a fractional pixel is what a phone camera fails to lock onto.
 */
@Composable
fun QrCode(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.semantics { contentDescription = description }) {
        val cell = floor(size.minDimension / modules.size).coerceAtLeast(1f)
        val side = cell * modules.size
        val origin = Offset(floor((size.width - side) / 2), floor((size.height - side) / 2))
        drawRect(Color.White, origin, Size(side, side))
        for (y in 0 until modules.size) {
            for (x in 0 until modules.size) {
                if (modules[x, y]) drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}
