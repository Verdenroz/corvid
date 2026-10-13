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
    fun `a code the server refuses says it was used or has expired`() =
        runTest {
            assertEquals("That code was used or has expired.", AuthFailure.BadCode.userMessage().resolve(environment))
        }

    @Test
    fun `a device with no passkey for the account says so`() =
        runTest {
            assertEquals(
                "No passkey for this account on this device.",
                PasskeyException.NoCredential.userMessage().resolve(environment),
            )
        }
}
