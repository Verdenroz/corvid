package com.verdenroz.corvid.core.auth

class AndroidRedirectUriProvider : RedirectUriProvider {
    override fun redirectUri(): String = ANDROID_REDIRECT_URI
}
