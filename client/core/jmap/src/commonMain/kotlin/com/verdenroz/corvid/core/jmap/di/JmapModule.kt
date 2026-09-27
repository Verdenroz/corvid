package com.verdenroz.corvid.core.jmap.di

import com.verdenroz.corvid.core.jmap.AuthLatch
import org.koin.dsl.module

val jmapModule =
    module {
        single { AuthLatch() }
    }
