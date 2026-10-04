package com.verdenroz.corvid.core.common.di

/** The Koin scope qualifier decision 10 requires: closing it drops every scoped
 *  repository and mail UI state, so nothing survives past a lock. */
class UnlockedScopeMarker
