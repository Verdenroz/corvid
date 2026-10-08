package com.verdenroz.corvid.core.discovery

import com.verdenroz.corvid.core.domain.SrvLookup
import org.koin.dsl.module

actual val platformDiscoveryModule =
    module {
        single<SrvLookup> { DnsJavaSrvLookup() }
    }
