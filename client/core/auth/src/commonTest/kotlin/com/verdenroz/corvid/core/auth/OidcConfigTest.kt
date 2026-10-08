package com.verdenroz.corvid.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class OidcConfigTest {
    @Test
    fun `requests the profile scope Stalwart needs to look the user up`() {
        val client = createOidcClient("https://idp.example.com/.well-known/openid-configuration", ANDROID_REDIRECT_URI)

        assertEquals("openid profile", client.config.scope)
    }
}
