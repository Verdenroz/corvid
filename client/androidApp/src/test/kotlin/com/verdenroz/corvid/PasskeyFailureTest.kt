package com.verdenroz.corvid

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.verdenroz.corvid.core.auth.PasskeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PasskeyFailureTest {
    @Test
    fun `dismissing either prompt is a cancellation`() {
        assertEquals(PasskeyException.Cancelled, passkeyFailure(CreateCredentialCancellationException()))
        assertEquals(PasskeyException.Cancelled, passkeyFailure(GetCredentialCancellationException()))
    }

    @Test
    fun `no passkey for the server is no credential`() {
        assertEquals(PasskeyException.NoCredential, passkeyFailure(NoCredentialException()))
    }

    @Test
    fun `a device with no provider or no way to ask is unsupported`() {
        val failures =
            listOf(
                CreateCredentialProviderConfigurationException(),
                GetCredentialProviderConfigurationException(),
                CreateCredentialNoCreateOptionException(),
                GetCredentialUnsupportedException(),
            )
        failures.forEach { assertEquals(PasskeyException.Unsupported, passkeyFailure(it)) }
    }

    @Test
    fun `a security error from either ceremony means the app is not tied to the server's domain`() {
        assertEquals(PasskeyException.NotAssociated, passkeyFailure(CreatePublicKeyCredentialDomException(SecurityError(), "rp")))
        assertEquals(PasskeyException.NotAssociated, passkeyFailure(GetPublicKeyCredentialDomException(SecurityError(), "rp")))
    }

    @Test
    fun `any other DOM error is a refusal in the platform's words`() {
        val refused = assertIs<PasskeyException.Refused>(passkeyFailure(CreatePublicKeyCredentialDomException(InvalidStateError(), "held")))

        assertEquals("held", refused.message)
    }

    @Test
    fun `an error the platform does not classify is a refusal in its words`() {
        assertEquals("boom", assertIs<PasskeyException.Refused>(passkeyFailure(GetCredentialUnknownException("boom"))).message)
        assertEquals("bang", assertIs<PasskeyException.Refused>(passkeyFailure(CreateCredentialUnknownException("bang"))).message)
    }
}
