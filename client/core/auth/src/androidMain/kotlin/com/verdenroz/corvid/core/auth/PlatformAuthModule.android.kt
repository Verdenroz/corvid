package com.verdenroz.corvid.core.auth

import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

/**
 * MainActivity must still call [AndroidCodeAuthFlowFactory.registerActivity] on this
 * exact singleton in onCreate, or a login started before that never gets its redirect.
 */
actual val platformAuthModule =
    module {
        single { AndroidCodeAuthFlowFactory(useWebView = false) }
        single<CodeAuthFlowFactory> { get<AndroidCodeAuthFlowFactory>() }
        single { PrivateCodeAuthFlowFactory(get<AndroidCodeAuthFlowFactory>()) }
        single<RedirectUriProvider> { AndroidRedirectUriProvider() }
    }
