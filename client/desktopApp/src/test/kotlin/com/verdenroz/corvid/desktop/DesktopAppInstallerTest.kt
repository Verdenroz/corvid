package com.verdenroz.corvid.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppInstallerTest {
    private val launcher = File("/home/u/.local/share/corvid/current/corvid/bin/corvid")

    @Test
    fun `relaunch drops the jpackage launcher variable and keeps the rest of the environment`() {
        val parent = mapOf("_JPACKAGE_LAUNCHER" to "1", "DISPLAY" to ":0")

        val env = relaunchCommand(1L, launcher, parent).environment()

        assertEquals(mapOf("DISPLAY" to ":0"), env.toMap())
    }
}
