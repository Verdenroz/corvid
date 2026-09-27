package com.verdenroz.corvid.app

import org.koin.core.Koin
import org.koin.core.scope.Scope

/** The Koin scope qualifier decision 10 requires: closing it drops every scoped
 *  repository and mail UI state, so nothing survives past a lock. */
class UnlockedScopeMarker

/**
 * Owns the one live [UnlockedScopeMarker] scope. The caller declares whatever
 * the newly opened scope should hold (a [com.verdenroz.corvid.core.jmap.JmapClient]
 * today, scoped repositories from slices 7/8 later); [close] is idempotent
 * since both drop triggers and sign-out call it.
 */
class UnlockedScopeHolder(private val koin: Koin) {
    private var scope: Scope? = null

    fun open(): Scope = scope ?: koin.createScope<UnlockedScopeMarker>(SCOPE_ID).also { scope = it }

    fun close() {
        scope?.close()
        scope = null
    }

    private companion object {
        const val SCOPE_ID = "unlocked"
    }
}
