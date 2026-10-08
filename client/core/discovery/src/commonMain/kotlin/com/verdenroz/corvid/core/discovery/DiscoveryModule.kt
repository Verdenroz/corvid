package com.verdenroz.corvid.core.discovery

import com.verdenroz.corvid.core.domain.DiscoveryDocumentSource
import org.koin.core.module.Module
import org.koin.dsl.module

val discoveryModule =
    module {
        single<DiscoveryDocumentSource> { HttpDiscoveryDocumentSource(get()) }
    }

/** Supplies the [com.verdenroz.corvid.core.domain.SrvLookup], which needs the platform's resolver settings. */
expect val platformDiscoveryModule: Module
