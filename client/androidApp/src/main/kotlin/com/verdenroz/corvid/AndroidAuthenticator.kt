package com.verdenroz.corvid

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.verdenroz.corvid.core.auth.Authenticator
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.model.ServerAddress
import java.lang.ref.WeakReference

/**
 * The system passkey prompt, through Credential Manager, so any passkey provider the person has set
 * up can answer. The prompt needs an activity to draw over, which [attach] holds only while one is
 * showing.
 */
class AndroidAuthenticator : Authenticator {
    private var activity: WeakReference<Activity>? = null

    fun attach(activity: Activity?) {
        this.activity = activity?.let(::WeakReference)
    }

    override suspend fun create(
        server: ServerAddress,
        options: String,
    ): String {
        val host = shown()
        return try {
            val response = CredentialManager.create(host).createCredential(host, CreatePublicKeyCredentialRequest(options))
            (response as CreatePublicKeyCredentialResponse).registrationResponseJson
        } catch (e: CreateCredentialException) {
            throw passkeyFailure(e)
        }
    }

    override suspend fun get(
        server: ServerAddress,
        options: String,
    ): String {
        val host = shown()
        return try {
            val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options)))
            val credential = CredentialManager.create(host).getCredential(host, request).credential
            (credential as? PublicKeyCredential)?.authenticationResponseJson
                ?: throw PasskeyException.Refused("the provider answered something other than a passkey")
        } catch (e: GetCredentialException) {
            throw passkeyFailure(e)
        }
    }

    private fun shown(): Activity = activity?.get() ?: throw PasskeyException.Refused("the app has no window to ask in")
}

/** What a Credential Manager failure means to the person, from its type or the DOM error behind it. */
internal fun passkeyFailure(failure: Exception): PasskeyException {
    val domError =
        when (failure) {
            is CreatePublicKeyCredentialDomException -> failure.domError
            is GetPublicKeyCredentialDomException -> failure.domError
            else -> null
        }
    return when {
        failure is CreateCredentialCancellationException || failure is GetCredentialCancellationException -> PasskeyException.Cancelled
        failure is NoCredentialException -> PasskeyException.NoCredential
        failure is CreateCredentialProviderConfigurationException ||
            failure is GetCredentialProviderConfigurationException ||
            failure is CreateCredentialNoCreateOptionException ||
            failure is GetCredentialUnsupportedException -> PasskeyException.Unsupported
        domError is SecurityError -> PasskeyException.NotAssociated
        else -> PasskeyException.Refused(failure.message)
    }
}
