package com.verdenroz.corvid.core.prefs

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxDirectoryTest {
    @Test
    fun `the desktop outbox sits beside the preferences file under the config directory`() {
        val koin = koinApplication { modules(platformOutboxDirectoryModule) }.koin

        assertEquals(userConfigDir().resolve("corvid").resolve("outbox"), koin.get<OutboxDirectory>().path)
    }
}
