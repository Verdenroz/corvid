package com.verdenroz.corvid.app

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.EndSessionRequest
import org.publicvalue.multiplatform.oidc.types.TokenRequest
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import org.publicvalue.multiplatform.oidc.types.remote.OpenIdConnectConfiguration

/** Only [refreshToken] is exercised by [TokenSource]; nothing here refreshes a token. */
class FakeOidcClient(private val refreshResult: suspend () -> AccessTokenResponse) : OpenIdConnectClient {
    override val config: OpenIdConnectClientConfig get() = TODO("not used by AppLockViewModel")
    override val discoverDocument: OpenIdConnectConfiguration? get() = null

    override suspend fun discover(configure: (HttpRequestBuilder.() -> Unit)?) = Unit

    override fun createAuthorizationCodeRequest(configure: (URLBuilder.() -> Unit)?): AuthCodeRequest = TODO("not used by AppLockViewModel")

    override fun createEndSessionRequest(
        idToken: String?,
        configure: (URLBuilder.() -> Unit)?,
    ): EndSessionRequest = TODO("not used by AppLockViewModel")

    override suspend fun endSession(
        idToken: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): HttpStatusCode = TODO("not used by AppLockViewModel")

    override suspend fun revokeToken(
        token: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): HttpStatusCode = TODO("not used by AppLockViewModel")

    override suspend fun exchangeToken(
        authCodeRequest: AuthCodeRequest,
        code: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): AccessTokenResponse = TODO("not used by AppLockViewModel")

    override suspend fun refreshToken(
        refreshToken: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): AccessTokenResponse = refreshResult()

    override suspend fun createAccessTokenRequest(
        authCodeRequest: AuthCodeRequest,
        code: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): TokenRequest = TODO("not used by AppLockViewModel")

    override suspend fun createRefreshTokenRequest(
        refreshToken: String,
        configure: (HttpRequestBuilder.() -> Unit)?,
    ): TokenRequest = TODO("not used by AppLockViewModel")
}
