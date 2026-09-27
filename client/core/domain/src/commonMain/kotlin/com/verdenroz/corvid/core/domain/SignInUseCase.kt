package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.RedirectUriProvider
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import com.verdenroz.corvid.core.model.ServerAddress
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

/**
 * Runs the PKCE authorization-code flow against the Pocket-ID instance the
 * server advertises at `/idp`, and hands the resulting tokens to
 * [TokenSource]. Never touches disk: the tokens live in [TokenSource] only.
 */
@OptIn(ExperimentalOpenIdConnect::class)
class SignInUseCase(
    private val authFlowFactory: CodeAuthFlowFactory,
    private val redirectUriProvider: RedirectUriProvider,
    private val tokenSource: TokenSource,
) {
    suspend operator fun invoke(server: ServerAddress) {
        val client =
            createOidcClient(
                discoveryUri = "${server.baseUrl}/idp/.well-known/openid-configuration",
                redirectUri = redirectUriProvider.redirectUri(),
            )
        val flow = authFlowFactory.createAuthFlow(client)
        val tokens = flow.getAccessToken()
        tokenSource.adopt(client, tokens)
    }
}
