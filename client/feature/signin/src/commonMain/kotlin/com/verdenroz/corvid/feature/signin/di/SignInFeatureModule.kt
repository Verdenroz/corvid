package com.verdenroz.corvid.feature.signin.di

import com.verdenroz.corvid.feature.signin.SignInViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val signInFeatureModule =
    module {
        viewModelOf(::SignInViewModel)
    }
