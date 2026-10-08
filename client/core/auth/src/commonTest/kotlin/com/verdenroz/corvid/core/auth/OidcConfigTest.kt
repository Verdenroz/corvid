package com.verdenroz.corvid.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class OidcConfigTest {
    private val discovery = "https://idp.example.com/.well-known/openid-configuration"

    @Test
    fun `requests the profile scope Stalwart needs to look the user up`() {
        val client = createOidcClient(discovery, ANDROID_REDIRECT_URI)

        assertEquals("openid profile", client.config.scope)
    }

    @Test
    fun `signs in as the daily client unless told otherwise`() {
        assertEquals("corvid", createOidcClient(discovery, ANDROID_REDIRECT_URI).config.clientId)
    }

    @Test
    fun `the admin client keeps the same scope and redirect`() {
        val client = createOidcClient(discovery, ANDROID_REDIRECT_URI, OIDC_ADMIN_CLIENT_ID)

        assertEquals("corvid-admin", client.config.clientId)
        assertEquals("openid profile", client.config.scope)
        assertEquals(ANDROID_REDIRECT_URI, client.config.redirectUri)
    }
}
