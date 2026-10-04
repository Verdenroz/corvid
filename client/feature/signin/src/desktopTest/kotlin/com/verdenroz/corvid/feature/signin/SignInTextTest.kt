package com.verdenroz.corvid.feature.signin

import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_failed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SignInTextTest {
    @Test
    fun `a failed sign-in carries the provider's own words`() =
        runTest {
            assertEquals("Sign-in failed: invalid_client", uiText(Res.string.signin_failed, UiText.FromServer("invalid_client")).resolve())
        }
}
