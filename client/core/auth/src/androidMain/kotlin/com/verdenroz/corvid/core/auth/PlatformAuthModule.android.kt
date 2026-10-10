package com.verdenroz.corvid.core.auth

import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

val privateAndroidFlowQualifier = named("private-android-flow")

/**
 * MainActivity must still call [AndroidCodeAuthFlowFactory.registerActivity] on both exact
 * singletons in onCreate, or a login started before that never gets its redirect. The private
 * one opens an ephemeral Custom Tab, which only Chrome 136+ honours; any other browser runs it
 * as a normal tab that shares the daily session.
 */
actual val platformAuthModule =
    module {
        single { AndroidCodeAuthFlowFactory(useWebView = false) }
        single<CodeAuthFlowFactory> { get<AndroidCodeAuthFlowFactory>() }
        single(privateAndroidFlowQualifier) { AndroidCodeAuthFlowFactory(useWebView = false, ephemeralSession = true) }
        single { PrivateCodeAuthFlowFactory(get<AndroidCodeAuthFlowFactory>(privateAndroidFlowQualifier)) }
        single<RedirectUriProvider> { AndroidRedirectUriProvider() }
    }
