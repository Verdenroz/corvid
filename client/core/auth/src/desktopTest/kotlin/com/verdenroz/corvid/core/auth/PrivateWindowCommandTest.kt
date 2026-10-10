package com.verdenroz.corvid.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val URL = "http://localhost:1234/authorize?a=b&c=d"

private val zen =
    """
    [Desktop Entry]
    Name=Zen Browser
    Exec=/opt/zen-browser-bin/zen-bin %u
    Actions=new-window;new-private-window;

    [Desktop Action new-window]
    Exec=/opt/zen-browser-bin/zen-bin %u
    Name=New Window

    [Desktop Action new-private-window]
    Exec=/opt/zen-browser-bin/zen-bin --private-window %u
    Name=New Private Window
    """.trimIndent()

class PrivateWindowCommandTest {
    @Test
    fun `a firefox-family entry opens the url through its private-window action`() {
        assertEquals(listOf("/opt/zen-browser-bin/zen-bin", "--private-window", URL), privateWindowCommand(zen, URL))
    }

    @Test
    fun `a chromium-style entry opens the url through its incognito action`() {
        val entry =
            """
            [Desktop Entry]
            Exec=/usr/bin/chromium %U

            [Desktop Action new-private-window]
            Name=New Incognito Window
            Exec=/usr/bin/chromium --incognito
            """.trimIndent()

        assertEquals(listOf("/usr/bin/chromium", "--incognito", URL), privateWindowCommand(entry, URL))
    }

    @Test
    fun `an entry with no private action yields nothing`() {
        val entry =
            """
            [Desktop Entry]
            Exec=/usr/bin/browser --private-window %u

            [Desktop Action new-window]
            Exec=/usr/bin/browser %u
            """.trimIndent()

        assertNull(privateWindowCommand(entry, URL))
    }

    @Test
    fun `other field codes are dropped and percent signs unescaped`() {
        val entry =
            """
            [Desktop Action private]
            Exec=browser %i %c --private-window --title=100%% %U
            """.trimIndent()

        assertEquals(listOf("browser", "--private-window", "--title=100%", URL), privateWindowCommand(entry, URL))
    }

    @Test
    fun `quoted arguments stay whole`() {
        val entry =
            """
            [Desktop Action private]
            Exec="/opt/my browser/bin" -private-window %u
            """.trimIndent()

        assertEquals(listOf("/opt/my browser/bin", "-private-window", URL), privateWindowCommand(entry, URL))
    }
}
