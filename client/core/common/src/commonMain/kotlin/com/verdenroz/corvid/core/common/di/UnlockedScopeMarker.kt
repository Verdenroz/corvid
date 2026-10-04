package com.verdenroz.corvid.core.common.di

/** The unlocked Koin scope's qualifier: closing it drops every scoped repository
 *  and mail UI state, so nothing survives past a lock. */
class UnlockedScopeMarker
