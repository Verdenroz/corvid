package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.di.dataModule
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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
    fun `repositories come from the unlocked scope and are dropped with it`() =
        runTest {
            val client = JmapClient.connect(createJmapHttpClient(FakeJmapServer().engine), AuthLatch(), "http://127.0.0.1:9999")
            val holder = UnlockedScopeHolder(koinApplication { modules(dataModule) }.koin)

            val scope = holder.open().also { it.declare(client) }
            val first = scope.get<ComposeRepository>()
            assertSame(first, scope.get<ComposeRepository>())
            holder.close()
            val second = holder.open().also { it.declare(client) }.get<ComposeRepository>()

            assertNotSame(first, second)
        }

    @Test
    fun `closing without ever opening is a no-op`() {
        val app = koinApplication { modules(module { scope<UnlockedScopeMarker> { scoped { Held() } } }) }
        val holder = UnlockedScopeHolder(app.koin)

        holder.close()
    }
}
