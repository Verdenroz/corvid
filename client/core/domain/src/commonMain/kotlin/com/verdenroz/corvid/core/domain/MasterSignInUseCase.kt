package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.OIDC_ADMIN_CLIENT_ID
import com.verdenroz.corvid.core.auth.RedirectUriProvider
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

interface MasterSignIn {
    suspend fun <T> withAdminToken(use: suspend (String) -> T): T
}

/**
 * A fresh passkey sign-in to the admin client at the issuer this session signed in through. The
 * token is lent to the caller's block and never handed to [TokenSource], so it is spent once and
 * dropped.
 */
@OptIn(ExperimentalOpenIdConnect::class)
class MasterSignInUseCase(
    private val authFlowFactory: CodeAuthFlowFactory,
    private val redirectUriProvider: RedirectUriProvider,
    private val tokenSource: TokenSource,
) : MasterSignIn {
    override suspend fun <T> withAdminToken(use: suspend (String) -> T): T {
        val discovery = discoveryUri(checkNotNull(tokenSource.issuerUrl) { "not signed in" })
        val adminClient = createOidcClient(discovery, redirectUriProvider.redirectUri(), OIDC_ADMIN_CLIENT_ID)
        return use(authFlowFactory.createAuthFlow(adminClient).getAccessToken().access_token)
    }
}
