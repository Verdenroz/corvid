package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.RedirectUriProvider
import com.verdenroz.corvid.core.auth.TokenSource
import com.verdenroz.corvid.core.auth.createOidcClient
import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.model.ServerAddress
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

/**
 * Runs the PKCE authorization-code flow against Pocket-ID and hands the
 * resulting tokens to [TokenSource]. Never touches disk: the tokens live in
 * [TokenSource] only. Clears the [AuthLatch] on success, since a stale trip
 * from before this sign-in would otherwise short-circuit every call forever.
 *
 * [issuer] is a separate input rather than derived from [server]: Pocket-ID
 * refuses an issuer URL with a path, so whether it sits at a subdomain or a
 * separate port is a deployment choice, not something this code can assume.
 */
@OptIn(ExperimentalOpenIdConnect::class)
class SignInUseCase(
    private val authFlowFactory: CodeAuthFlowFactory,
    private val redirectUriProvider: RedirectUriProvider,
    private val tokenSource: TokenSource,
    private val authLatch: AuthLatch,
) {
    suspend operator fun invoke(
        server: ServerAddress,
        issuer: String,
    ) {
        val client = createOidcClient(discoveryUri = discoveryUri(issuer), redirectUri = redirectUriProvider.redirectUri())
        val flow = authFlowFactory.createAuthFlow(client)
        val tokens = flow.getAccessToken()
        tokenSource.adopt(client, tokens, server)
        authLatch.clear()
    }
}

internal fun discoveryUri(issuer: String): String {
    val trimmed = issuer.trimEnd('/')
    // The discovery document names the token endpoint a refresh sends the
    // refresh token to, so an http issuer would leak it in the clear.
    require(trimmed.startsWith("https://")) { "the identity provider must be reached over https" }
    return "$trimmed/.well-known/openid-configuration"
}
