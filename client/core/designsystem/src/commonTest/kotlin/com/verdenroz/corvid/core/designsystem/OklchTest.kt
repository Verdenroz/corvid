package com.verdenroz.corvid.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OklchTest {
    @Test
    fun `full lightness with no chroma is white`() {
        assertRgb(listOf(0xFF, 0xFF, 0xFF), oklch(1f, 0f, 0f))
    }

    @Test
    fun `zero lightness is black whatever the hue`() {
        assertRgb(listOf(0x00, 0x00, 0x00), oklch(0f, 0f, 295f))
    }

    @Test
    fun `the light rail matches an independent conversion`() {
        assertRgb(listOf(0xF6, 0xF5, 0xFA), LightCorvidColors.rail)
    }

    @Test
    fun `the dark accent matches an independent conversion`() {
        assertRgb(listOf(0xB6, 0xA2, 0xF3), DarkCorvidColors.accent)
    }

    @Test
    fun `alpha passes through unchanged`() {
        assertEquals(0.5f, oklch(0.5f, 0.1f, 120f, alpha = 0.5f).alpha, 1f / 255)
    }

    private fun assertRgb(
        expected: List<Int>,
        actual: Color,
    ) {
        val channels = listOf(actual.red, actual.green, actual.blue).map { (it * 255).roundToInt() }
        assertTrue(expected.zip(channels).all { (want, got) -> abs(want - got) <= 1 }, "expected $expected, got $channels")
    }
}
