package com.verdenroz.corvid.core.data.di

import com.verdenroz.corvid.core.common.di.UnlockedScopeMarker
import com.verdenroz.corvid.core.data.AppReleaseRepository
import com.verdenroz.corvid.core.data.ComposeRepository
import com.verdenroz.corvid.core.data.FiltersRepository
import com.verdenroz.corvid.core.data.MailboxRepository
import com.verdenroz.corvid.core.data.NoticeRepository
import com.verdenroz.corvid.core.data.SenderRepository
import com.verdenroz.corvid.core.data.UpdateRepository
import org.koin.dsl.module

val dataModule =
    module {
        scope<UnlockedScopeMarker> {
            scoped { MailboxRepository(jmapClient = get()) }
            scoped { ComposeRepository(jmapClient = get(), mailboxRepository = get()) }
            scoped { FiltersRepository(jmapClient = get()) }
            scoped { UpdateRepository(jmapClient = get()) }
            scoped { AppReleaseRepository(jmapClient = get()) }
            scoped { NoticeRepository(jmapClient = get()) }
            scoped { SenderRepository(jmapClient = get(), mailboxRepository = get()) }
        }
    }
