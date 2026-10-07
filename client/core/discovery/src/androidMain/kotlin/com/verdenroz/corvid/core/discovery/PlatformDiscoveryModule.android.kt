package com.verdenroz.corvid.core.discovery

import com.verdenroz.corvid.core.domain.SrvLookup
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.xbill.DNS.config.AndroidResolverConfigProvider

actual val platformDiscoveryModule =
    module {
        single<SrvLookup> {
            // dnsjava finds Android's resolvers only through a Context.
            AndroidResolverConfigProvider.setContext(androidContext())
            DnsJavaSrvLookup()
        }
    }
