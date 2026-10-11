package com.verdenroz.corvid.core.auth

/**
 * The platform's passkey prompt. Both calls take and return WebAuthn Level 3 JSON, the shape the
 * sentinel serves and Credential Manager speaks, so the platform code never parses a ceremony.
 */
interface Authenticator {
    /** Makes a passkey from `PublicKeyCredentialCreationOptionsJSON`; answers the `RegistrationResponseJSON`. */
    suspend fun create(options: String): String

    /** Signs in with a passkey from `PublicKeyCredentialRequestOptionsJSON`; answers the `AuthenticationResponseJSON`. */
    suspend fun get(options: String): String
}

/** Why the platform's passkey prompt did not produce a credential. */
sealed class PasskeyException(message: String? = null) : Exception(message) {
    /** The person dismissed the prompt. */
    data object Cancelled : PasskeyException()

    /** The device holds no passkey for this server. */
    data object NoCredential : PasskeyException()

    /** The platform could not tie this app to the server's domain; its `assetlinks.json` does not list this build. */
    data object NotAssociated : PasskeyException()

    /** This device has nothing to make or use passkeys with. */
    data object Unsupported : PasskeyException()

    /** The platform refused for a reason of its own, in its words. */
    class Refused(detail: String?) : PasskeyException(detail)
}
