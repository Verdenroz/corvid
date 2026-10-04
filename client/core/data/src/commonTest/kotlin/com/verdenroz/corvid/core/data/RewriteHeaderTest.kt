package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.Rewrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val OURS = "stripped=2 proxied=3 hosts=4 trackers=2 tracker-hosts=beacon.ads.test,px.tracker.test"

class RewriteHeaderTest {
    @Test
    fun `a single genuine value shows its counts and named hosts`() {
        assertEquals(
            Rewrite.Stripped(
                stripped = 2,
                proxied = 3,
                hosts = 4,
                trackers = 2,
                named = setOf("beacon.ads.test", "px.tracker.test"),
                omitted = 0,
            ),
            readRewrite(listOf(OURS)),
        )
    }

    @Test
    fun `a clean message and a skipped one read as such`() {
        assertEquals(Rewrite.Clean, readRewrite(listOf("none")))
        assertEquals(Rewrite.Skipped, readRewrite(listOf(" skipped ")))
    }

    @Test
    fun `hosts the header had no room for are counted as omitted`() {
        val stripped = readRewrite(listOf("stripped=200 proxied=0 hosts=0 trackers=200 tracker-hosts=a.test tracker-hosts-omitted=199"))

        assertEquals(199, (stripped as Rewrite.Stripped).omitted)
    }

    @Test
    fun `a forged copy beside ours shows nothing`() {
        assertNull(readRewrite(listOf("stripped=9 proxied=0 hosts=0 trackers=9", OURS)))
        assertNull(readRewrite(listOf("none", OURS)))
    }

    @Test
    fun `a lone forged-looking value still shows, since only the server can tell it apart`() {
        assertEquals(Rewrite.Clean, readRewrite(listOf("none")))
    }

    @Test
    fun `no header means the milter never ran, not a clean message`() {
        assertNull(readRewrite(emptyList()))
    }

    @Test
    fun `a header from somewhere else is refused`() {
        for (value in listOf(
            "",
            "yes",
            "stripped=1",
            "stripped=1 proxied=1 hosts=1",
            "stripped=x proxied=1 hosts=1 trackers=1",
            "stripped=1 proxied=1 hosts=1 trackers=1 extra=2",
            "stripped=-1 proxied=1 hosts=1 trackers=1",
        )) {
            assertNull(readRewrite(listOf(value)), "$value was accepted")
        }
    }
}
