package com.verdenroz.corvid

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidConnectivityTest {
    @Test
    fun `a validated network with internet is online`() {
        assertTrue(isUsable(hasInternet = true, validated = true))
    }

    @Test
    fun `a network that is not validated yet is offline`() {
        assertFalse(isUsable(hasInternet = true, validated = false))
    }

    @Test
    fun `a network without internet is offline`() {
        assertFalse(isUsable(hasInternet = false, validated = true))
    }
}
