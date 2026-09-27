package com.verdenroz.corvid.core.keyring.di

import com.verdenroz.corvid.core.keyring.KeyHolder
import org.koin.dsl.module

val keyringModule =
    module {
        single { KeyHolder() }
    }
