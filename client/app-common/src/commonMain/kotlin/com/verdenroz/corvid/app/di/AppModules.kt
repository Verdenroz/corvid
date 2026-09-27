package com.verdenroz.corvid.app.di

import com.verdenroz.corvid.core.auth.di.authModule
import com.verdenroz.corvid.core.common.di.commonModule
import com.verdenroz.corvid.core.domain.di.domainModule
import com.verdenroz.corvid.core.jmap.di.jmapModule
import com.verdenroz.corvid.core.keyring.di.keyringModule
import com.verdenroz.corvid.feature.signin.di.signInFeatureModule
import org.koin.core.module.Module

val appModules: List<Module> =
    listOf(commonModule, jmapModule, authModule, domainModule, keyringModule, appLockModule, signInFeatureModule)
