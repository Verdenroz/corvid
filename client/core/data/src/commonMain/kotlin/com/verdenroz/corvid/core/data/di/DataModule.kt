package com.verdenroz.corvid.core.data.di

import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import org.koin.dsl.module

val dataModule =
    module {
        scope<UnlockedScopeMarker> {
            scoped { MailboxRepository(jmapClient = get()) }
            scoped { ComposeRepository(jmapClient = get()) }
            scoped { FiltersRepository(jmapClient = get()) }
        }
    }
