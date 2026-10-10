package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.OIDC_ADMIN_CLIENT_ID
import com.verdenroz.corvid.core.auth.OIDC_CLIENT_ID
import com.verdenroz.corvid.core.auth.RedirectUriProvider
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

fun interface MasterSignIn {
    suspend operator fun invoke(): String
}

/**
 * A fresh passkey sign-in to the admin client at the issuer this session signed in through.
 * Pocket-ID refuses the admin client to a browser still signed in as the mail user, so the
 * browser's session is ended first and the admin's afterwards. Returns only the access token:
 * it is never handed to [TokenSource], so the caller spends it once and drops it.
 */
@OptIn(ExperimentalOpenIdConnect::class)
class MasterSignInUseCase(
    private val authFlowFactory: CodeAuthFlowFactory,
    private val redirectUriProvider: RedirectUriProvider,
    private val tokenSource: TokenSource,
) : MasterSignIn {
    override suspend operator fun invoke(): String {
        val discovery = discoveryUri(checkNotNull(tokenSource.issuerUrl) { "not signed in" })
        val redirectUri = redirectUriProvider.redirectUri()
        val dailyClient = createOidcClient(discovery, redirectUri, OIDC_CLIENT_ID)
        authFlowFactory.createEndSessionFlow(dailyClient).endSession(tokenSource.idToken)
        val adminClient = createOidcClient(discovery, redirectUri, OIDC_ADMIN_CLIENT_ID)
        val admin = authFlowFactory.createAuthFlow(adminClient).getAccessToken()
        try {
            authFlowFactory.createEndSessionFlow(adminClient).endSession(admin.id_token)
        } catch (_: OpenIdConnectException) {
            // the token is already in hand
        }
        return admin.access_token
    }
}
