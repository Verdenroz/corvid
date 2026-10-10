package com.verdenroz.corvid.core.auth

import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod

const val OIDC_CLIENT_ID = "corvid"
const val OIDC_ADMIN_CLIENT_ID = "corvid-admin"
const val ANDROID_REDIRECT_URI = "com.verdenroz.corvid:/oauth2redirect"

fun desktopRedirectUri(port: Int): String = "http://127.0.0.1:$port/callback"

/** Pocket-ID as a public PKCE client; app passwords cover IMAP/SMTP, this covers JMAP. */
fun createOidcClient(
    discoveryUri: String,
    redirectUri: String,
    clientId: String = OIDC_CLIENT_ID,
): OpenIdConnectClient =
    OpenIdConnectClient(discoveryUri = discoveryUri) {
        this.clientId = clientId
        scope = "openid profile"
        codeChallengeMethod = CodeChallengeMethod.S256
        this.redirectUri = redirectUri
    }
