package com.verdenroz.corvid.core.common.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

val applicationScopeQualifier = named("corvid.applicationScope")

val commonModule =
    module {
        single(applicationScopeQualifier) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }
