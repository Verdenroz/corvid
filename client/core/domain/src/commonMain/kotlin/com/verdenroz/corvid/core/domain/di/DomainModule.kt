package com.verdenroz.corvid.core.domain.di

import com.verdenroz.corvid.core.domain.DiscoverAccount
import com.verdenroz.corvid.core.domain.EnrollPasskey
import com.verdenroz.corvid.core.domain.EnrollPasskeyUseCase
import com.verdenroz.corvid.core.domain.MasterSignIn
import com.verdenroz.corvid.core.domain.MasterSignInUseCase
import com.verdenroz.corvid.core.domain.SignIn
import com.verdenroz.corvid.core.domain.SignInUseCase
import org.koin.dsl.module

val domainModule =
    module {
        factory<SignIn> {
            SignInUseCase(authApi = get(), authenticator = get(), tokenSource = get(), authLatch = get())
        }
        factory<EnrollPasskey> { EnrollPasskeyUseCase(authApi = get(), authenticator = get()) }
        factory<MasterSignIn> { MasterSignInUseCase(authApi = get(), authenticator = get(), tokenSource = get()) }
        factory { DiscoverAccount(srvLookup = get(), documents = get()) }
    }
