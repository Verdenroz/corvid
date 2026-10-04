package com.verdenroz.corvid.app

import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import org.koin.core.Koin
import org.koin.core.scope.Scope

/**
 * Owns the one live [UnlockedScopeMarker] scope. The caller declares whatever
 * the newly opened scope should hold (the [com.verdenroz.corvid.core.jmap.JmapClient]
 * the scoped repositories are built from); [close] is idempotent
 * since both drop triggers and sign-out call it.
 */
class UnlockedScopeHolder(private val koin: Koin) {
    private var scope: Scope? = null

    fun open(): Scope = scope ?: koin.createScope<UnlockedScopeMarker>(SCOPE_ID).also { scope = it }

    /** The live scope; only the lock gate opens one, so a screen asking after a lock gets an error. */
    fun current(): Scope = checkNotNull(scope) { "the unlocked scope is closed" }

    fun close() {
        scope?.close()
        scope = null
    }

    private companion object {
        const val SCOPE_ID = "unlocked"
    }
}
