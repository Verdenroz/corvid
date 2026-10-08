package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.OIDC_ADMIN_CLIENT_ID
import com.verdenroz.corvid.core.auth.RedirectUriProvider
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

fun interface MasterSignIn {
    suspend operator fun invoke(): String
}

/**
 * A fresh passkey sign-in to the admin client at the issuer this session signed in through.
 * Returns only the access token: it is never handed to [TokenSource], so the caller spends it
 * once and drops it.
 */
@OptIn(ExperimentalOpenIdConnect::class)
class MasterSignInUseCase(
    private val authFlowFactory: CodeAuthFlowFactory,
    private val redirectUriProvider: RedirectUriProvider,
    private val tokenSource: TokenSource,
) : MasterSignIn {
    override suspend operator fun invoke(): String {
        val issuer = checkNotNull(tokenSource.issuerUrl) { "not signed in" }
        val client = createOidcClient(discoveryUri(issuer), redirectUriProvider.redirectUri(), OIDC_ADMIN_CLIENT_ID)
        return authFlowFactory.createAuthFlow(client).getAccessToken().access_token
    }
}
