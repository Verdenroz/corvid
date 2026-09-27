package com.verdenroz.corvid.core.domain

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.EndSessionFlow
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/** [getAccessToken] is overridden directly; the rest of the flow is never exercised by [SignInUseCase]. */
class FakeCodeAuthFlow(private val result: suspend () -> AccessTokenResponse) : CodeAuthFlow {
    override suspend fun getAccessToken(
        configureAuthUrl: (URLBuilder.() -> Unit)?,
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?,
    ): AccessTokenResponse = result()

    override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest = TODO("not used by SignInUseCase")

    override suspend fun canContinueLogin(): Boolean = TODO("not used by SignInUseCase")

    override suspend fun continueLogin(configureTokenExchange: (HttpRequestBuilder.() -> Unit)?): AccessTokenResponse =
        TODO("not used by SignInUseCase")
}

class FakeCodeAuthFlowFactory(private val flow: CodeAuthFlow) : CodeAuthFlowFactory {
    override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow = flow

    override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow = TODO("not used by SignInUseCase")
}
