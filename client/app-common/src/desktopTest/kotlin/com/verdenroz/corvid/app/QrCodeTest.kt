package com.verdenroz.corvid.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrCodeTest {
    private val link = "corvid-approve:0123456789abcdef0123456789abcdef?code=042917"

    @Test
    fun `the code is a square with a light quiet zone and dark finder corners`() {
        val modules = qrModules(link)

        assertTrue(modules.size in 29..49)
        assertFalse(modules[0, 0])
        assertTrue(modules[4, 4])
        assertTrue(modules[modules.size - 5, 4])
        assertTrue(modules[4, modules.size - 5])
    }

    @Test
    fun `the same text encodes to the same modules`() {
        val first = qrModules(link)
        val second = qrModules(link)

        assertEquals(first.size, second.size)
        assertTrue((0 until first.size).all { y -> (0 until first.size).all { x -> first[x, y] == second[x, y] } })
    }
}
