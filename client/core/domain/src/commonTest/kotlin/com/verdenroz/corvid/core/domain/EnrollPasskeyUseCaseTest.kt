package com.verdenroz.corvid.core.domain

import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.FakeAuthApi
import com.verdenroz.corvid.core.testing.FakeAuthenticator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val SERVER = ServerAddress("https://mail.example.test")

class EnrollPasskeyUseCaseTest {
    private val api = FakeAuthApi()
    private val authenticator = FakeAuthenticator()
    private val enroll = EnrollPasskeyUseCase(api, authenticator)

    @Test
    fun `the code's options go to the prompt and the credential it makes goes back`() =
        runTest {
            api.registers = PasskeyRole.Master

            val role = enroll(SERVER, "ABCD-EFGH")

            assertEquals(PasskeyRole.Master, role)
            assertEquals(listOf("create creation-options:ABCD-EFGH"), authenticator.calls)
            assertEquals(listOf("registerOptions ABCD-EFGH", "registerFinish created:creation-options:ABCD-EFGH"), api.calls)
        }

    @Test
    fun `a code the server refuses never reaches the prompt`() =
        runTest {
            api.optionsFailure = AuthFailure.BadCode

            assertFailsWith<AuthFailure.BadCode> { enroll(SERVER, "x") }

            assertEquals(emptyList(), authenticator.calls)
        }

    @Test
    fun `a dismissed prompt finishes nothing`() =
        runTest {
            authenticator.failure = PasskeyException.Cancelled

            assertFailsWith<PasskeyException.Cancelled> { enroll(SERVER, "ABCD-EFGH") }

            assertEquals(listOf("registerOptions ABCD-EFGH"), api.calls)
        }
}
