package com.verdenroz.corvid.app

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotSame

private class Held

class UnlockedScopeHolderTest {
    @Test
    fun `no screen state survives a lock, a reopened scope never returns the old instance`() {
        val app = koinApplication { modules(module { scope<UnlockedScopeMarker> { scoped { Held() } } }) }
        val holder = UnlockedScopeHolder(app.koin)

        val first = holder.open().get<Held>()
        holder.close()
        val second = holder.open().get<Held>()

        assertNotSame(first, second)
    }

    @Test
    fun `closing without ever opening is a no-op`() {
        val app = koinApplication { modules(module { scope<UnlockedScopeMarker> { scoped { Held() } } }) }
        val holder = UnlockedScopeHolder(app.koin)

        holder.close()
    }
}
