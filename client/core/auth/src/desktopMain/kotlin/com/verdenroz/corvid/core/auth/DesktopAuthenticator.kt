package com.verdenroz.corvid.core.auth

import com.verdenroz.corvid.core.model.ServerAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Passkeys on a security key, through the system's libfido2. The ceremony's client data is built
 * here, with the dialled address as the origin, since no browser stands between the app and the key.
 * Only the first key found is used. [keys] is null where libfido2 is not installed.
 */
class DesktopAuthenticator internal constructor(
    private val keys: SecurityKeys?,
    private val prompt: SecurityKeyPrompt,
) : Authenticator {
    constructor(prompt: SecurityKeyPrompt) : this(Fido2Library.load()?.let(::Fido2SecurityKeys), prompt)

    override suspend fun create(
        server: ServerAddress,
        options: String,
    ): String {
        val (challenge, creation) = parsed { creationOptions(options) }
        val clientData = clientDataJson("webauthn.create", challenge, originOf(server))
        val hash = sha256(clientData)
        return registrationResponse(clientData, withKey { key -> verified(key) { pin -> key.make(creation, hash, pin) } })
    }

    override suspend fun get(
        server: ServerAddress,
        options: String,
    ): String {
        val (challenge, request) = parsed { requestOptions(options) }
        val clientData = clientDataJson("webauthn.get", challenge, originOf(server))
        val hash = sha256(clientData)
        return authenticationResponse(clientData, withKey { key -> verified(key) { pin -> key.assert(request, hash, pin) } })
    }

    private fun <T> parsed(parse: () -> T): T =
        try {
            parse()
        } catch (e: IllegalArgumentException) {
            throw PasskeyException.Refused("the server's options could not be read: ${e.message}")
        } catch (e: NoSuchElementException) {
            throw PasskeyException.Refused("the server's options could not be read: ${e.message}")
        }

    private suspend fun <T> withKey(use: suspend (SecurityKey) -> T): T {
        val available = keys ?: throw PasskeyException.Unsupported
        val key =
            withContext(Dispatchers.IO) { available.first() }
                ?: throw PasskeyException.Refused("no security key was found; plug it in and try again")
        try {
            return use(key)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { key.close() }
        }
    }

    /** Runs [call] with the PIN the key wants, asking again while it is wrong. */
    private suspend fun <T> verified(
        key: SecurityKey,
        call: (String?) -> T,
    ): T {
        var pin: String? =
            when (key.verification) {
                Verification.Pin -> prompt.askPin(wrong = false) ?: throw PasskeyException.Cancelled
                Verification.BuiltIn -> null
                Verification.None -> throw PasskeyException.Refused("the key has no PIN or fingerprint set; set one, then try again")
            }
        while (true) {
            try {
                return prompt.touching(onCancel = key::cancel) { blocking(key) { call(pin) } }
            } catch (e: SecurityKeyException) {
                if (e.error != SecurityKeyError.PinInvalid) throw failure(e)
                pin = prompt.askPin(wrong = true) ?: throw PasskeyException.Cancelled
            } catch (e: UnsatisfiedLinkError) {
                throw PasskeyException.Unsupported
            }
        }
    }

    /** A blocked call is ended through the key, since a thread stuck in libfido2 cannot be interrupted. */
    private suspend fun <T> blocking(
        key: SecurityKey,
        call: () -> T,
    ): T =
        coroutineScope {
            val running = async(Dispatchers.IO) { call() }
            try {
                running.await()
            } catch (e: CancellationException) {
                key.cancel()
                throw e
            }
        }
}

internal fun failure(e: SecurityKeyException): PasskeyException =
    when (e.error) {
        SecurityKeyError.PinNotSet -> PasskeyException.Refused("the key has no PIN set; set one, then try again")
        SecurityKeyError.PinBlocked -> PasskeyException.Refused("the key's PIN is blocked; it has to be reset")
        SecurityKeyError.PinAuthBlocked -> PasskeyException.Refused("too many wrong PINs; unplug the key, plug it in and try again")
        SecurityKeyError.NoCredentials -> PasskeyException.NoCredential
        SecurityKeyError.CredentialExcluded -> PasskeyException.Refused("this key already holds a passkey for the server")
        SecurityKeyError.NotTouched, SecurityKeyError.Cancelled -> PasskeyException.Cancelled
        SecurityKeyError.Unsupported -> PasskeyException.Unsupported
        SecurityKeyError.PinInvalid, SecurityKeyError.Other -> PasskeyException.Refused(e.message)
    }
