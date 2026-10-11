package com.verdenroz.corvid.feature.signin

import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SignInTextTest {
    private val environment = composedResourceEnvironment()

    @Test
    fun `a refused prompt carries the platform's own words`() =
        runTest {
            assertEquals(
                "The passkey prompt was refused: blocked by policy",
                PasskeyException.Refused("blocked by policy").userMessage().resolve(environment),
            )
        }

    @Test
    fun `a code the server refuses says it is not valid or has expired`() =
        runTest {
            assertEquals(
                "That enrollment code is not valid, or it has expired.",
                AuthFailure.BadCode.userMessage().resolve(environment),
            )
        }
}
