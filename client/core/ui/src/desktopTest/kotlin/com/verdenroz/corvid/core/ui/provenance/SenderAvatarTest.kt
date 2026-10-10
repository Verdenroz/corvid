package com.verdenroz.corvid.core.ui.provenance

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NAMES = listOf("Ada", "noreply-dmarc-support@google.com", "Corvid Sentinel", "Harvey Tseng", "x", "Zoë", "GitHub", "Maya Lin")

class SenderAvatarTest {
    @Test
    fun `an avatar fill is the same for the same name`() {
        assertEquals(avatarColor("Ada", dark = false), avatarColor("Ada", dark = false))
    }

    @Test
    fun `every fill stays a low chroma neutral in both themes`() {
        for (dark in listOf(false, true)) {
            for (name in NAMES) {
                val lab = avatarColor(name, dark).convert(ColorSpaces.Oklab)
                assertTrue(hypot(lab.green, lab.blue) < 0.04f, "$name dark=$dark")
            }
        }
    }

    @Test
    fun `the initial skips leading punctuation`() {
        assertEquals("A", initial("\"ada"))
    }
}
