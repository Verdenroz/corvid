package com.verdenroz.corvid.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.unlock(
    presence: AppPresence,
    navigator: Navigator,
) {
    backgroundScope.launch { presence.deliverTo(navigator) }
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenRequestsTest {
    private val presence = AppPresence()
    private val holder = RecordingHolder()

    @Test
    fun `a digest tapped while locked opens after the next unlock`() =
        runTest {
            presence.requestDigest("d7")
            val navigator = navigator(holder)

            unlock(presence, navigator)

            assertEquals(Route.Digest("d7"), navigator.route)
        }

    @Test
    fun `a request taken once does not reopen on the next unlock`() =
        runTest {
            presence.requestDigest("d7")
            unlock(presence, navigator(holder))

            val later = navigator(holder)
            unlock(presence, later)

            assertEquals(Route.Home(HomeTab.Inbox), later.route)
            assertNull(presence.pending.value)
        }

    @Test
    fun `an inbox tap while locked shows the inbox after unlock`() =
        runTest {
            presence.requestInbox()
            val navigator = navigator(holder, Route.Settings)

            unlock(presence, navigator)

            assertEquals(Route.Home(HomeTab.Inbox), navigator.route)
            assertEquals(1, navigator.depth)
        }

    @Test
    fun `a newer tap replaces an older pending one`() =
        runTest {
            presence.requestDigest("d7")
            presence.requestInbox()

            assertEquals(OpenRequest.Inbox, presence.take())
            assertNull(presence.take())
        }

    @Test
    fun `a tap while unlocked is delivered at once`() =
        runTest {
            val navigator = navigator(holder)
            unlock(presence, navigator)

            presence.requestDigest("d8")
            runCurrent()

            assertEquals(Route.Digest("d8"), navigator.route)
        }
}
