package com.verdenroz.corvid.core.auth

import org.koin.dsl.module
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.appsupport.JvmCodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.appsupport.webserver.SimpleKtorWebserver
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

@OptIn(ExperimentalOpenIdConnect::class)
actual val platformAuthModule =
    module {
        single { DesktopRedirectUriProvider() }
        single<RedirectUriProvider> { get<DesktopRedirectUriProvider>() }
        single {
            val port = get<DesktopRedirectUriProvider>().port
            JvmCodeAuthFlowFactory(webserverProvider = { SimpleKtorWebserver(port = port) })
        }
        single<CodeAuthFlowFactory> { get<JvmCodeAuthFlowFactory>() }
        single {
            val port = get<DesktopRedirectUriProvider>().port
            PrivateCodeAuthFlowFactory(
                JvmCodeAuthFlowFactory(webserverProvider = { SimpleKtorWebserver(port = port) }, openUrl = ::openInPrivateWindow),
            )
        }
    }
