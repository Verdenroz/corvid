package com.verdenroz.corvid

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationIdsTest {
    @Test
    fun `no two notifications share an id`() {
        assertEquals(NOTIFICATION_IDS.size, NOTIFICATION_IDS.toSet().size)
    }
}
