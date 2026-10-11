package com.verdenroz.corvid.core.keyring.di

import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.keyring.DeviceApprovalRepository
import com.verdenroz.corvid.core.keyring.KeyHolder
import com.verdenroz.corvid.core.keyring.PushEndpointRepository
import org.koin.dsl.module

val keyringModule =
    module {
        single { KeyHolder() }
        scope<UnlockedScopeMarker> {
            scoped { DeviceApprovalRepository(jmapClient = get()) }
            scoped { PushEndpointRepository(jmapClient = get()) }
        }
    }
