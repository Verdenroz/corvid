package com.verdenroz.corvid.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val THEME = stringPreferencesKey("theme")
private val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
private val NOTICES_SEEN = longPreferencesKey("notices_seen")
private val DIGEST_SEEN = stringPreferencesKey("digest_seen")
private val NEW_MAIL_NOTIFICATIONS = booleanPreferencesKey("new_mail_notifications")
private val ACCOUNT_SERVER = stringPreferencesKey("account_server")

// Written by builds that signed in through an identity provider; read nowhere, removed on the next sign-in.
private val ACCOUNT_ISSUER = stringPreferencesKey("account_issuer")
private val ACCOUNT_EMAIL = stringPreferencesKey("account_email")
private val PUSH_STATUS = stringPreferencesKey("push_status")
private val PUSH_URL = stringPreferencesKey("push_url")
private val PUSH_P256DH = stringPreferencesKey("push_p256dh")
private val PUSH_AUTH = stringPreferencesKey("push_auth")
private val PUSH_TOKEN = stringPreferencesKey("push_token")
private val STREAM_CURSOR = stringPreferencesKey("stream_cursor")

private fun deviceNameKey(server: String) = stringPreferencesKey("device_name.$server")

/**
 * [settings] starts at [initial], which the platform reads synchronously before the first frame
 * so the lock screen already follows the chosen theme.
 */
class DevicePreferences(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    initial: DeviceSettings,
) {
    val settings: StateFlow<DeviceSettings> = store.data.map(::decode).stateIn(scope, SharingStarted.Eagerly, initial)

    suspend fun setTheme(theme: ThemeChoice) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setAutoLock(autoLock: AutoLock) {
        store.edit { it[AUTO_LOCK_MINUTES] = autoLock.minutes }
    }

    suspend fun setNoticesSeen(id: Long) {
        store.edit { it[NOTICES_SEEN] = id }
    }

    suspend fun setDigestSeen(id: String) {
        store.edit { it[DIGEST_SEEN] = id }
    }

    suspend fun setNewMailNotifications(on: Boolean) {
        store.edit { it[NEW_MAIL_NOTIFICATIONS] = on }
    }

    suspend fun rememberAccount(account: RememberedAccount) {
        store.edit {
            it[ACCOUNT_SERVER] = account.server
            it.remove(ACCOUNT_ISSUER)
            if (account.email == null) it.remove(ACCOUNT_EMAIL) else it[ACCOUNT_EMAIL] = account.email
        }
    }

    suspend fun setPushRegistering() {
        store.edit { it.writePush(PushSettings(PushStatus.Registering)) }
    }

    /** A new endpoint from the distributor, which replaces any earlier one and waits to be sent. */
    suspend fun setPushSubscription(subscription: PushSubscription) {
        store.edit { it.writePush(PushSettings(PushStatus.Unsent, subscription)) }
    }

    /** Forgets the endpoint; the server is told on the next sync. */
    suspend fun removePush() {
        store.edit { it.writePush(PushSettings(PushStatus.Removing)) }
    }

    suspend fun setPushOffOnServer() {
        store.edit { it.writePush(PushSettings(PushStatus.OffOnServer)) }
    }

    suspend fun setPushGoogleUnavailable() {
        store.edit { it.writePush(PushSettings(PushStatus.GoogleUnavailable)) }
    }

    suspend fun setPushFailed() {
        store.edit { it.writePush(PushSettings(PushStatus.Failed)) }
    }

    /** Records the server's answer for [subscription], unless the connector has replaced it since. */
    suspend fun recordPushAnswer(
        subscription: PushSubscription,
        status: PushStatus,
        streamToken: String? = null,
    ) {
        store.edit {
            if (decodePush(it).subscription == subscription) it.writePush(PushSettings(status, subscription, streamToken))
        }
    }

    /** The id of the last event read from the own connection, to resume after. */
    suspend fun streamCursor(): String? = store.data.first()[STREAM_CURSOR]

    suspend fun setStreamCursor(id: String) {
        store.edit { it[STREAM_CURSOR] = id }
    }

    /** Ends a removal the server has taken, unless background notifications were turned back on meanwhile. */
    suspend fun finishPushRemoval() {
        store.edit { if (decodePush(it).status == PushStatus.Removing) it.writePush(PushSettings()) }
    }

    suspend fun deviceName(server: String): String? = store.data.first()[deviceNameKey(server)]

    suspend fun setDeviceName(
        server: String,
        name: String,
    ) {
        store.edit { it[deviceNameKey(server)] = name }
    }
}

internal fun decode(preferences: Preferences): DeviceSettings {
    val defaults = DeviceSettings()
    return DeviceSettings(
        theme = ThemeChoice.entries.firstOrNull { it.name == preferences[THEME] } ?: defaults.theme,
        autoLock = AutoLock.entries.firstOrNull { it.minutes == preferences[AUTO_LOCK_MINUTES] } ?: defaults.autoLock,
        noticesSeen = preferences[NOTICES_SEEN] ?: defaults.noticesSeen,
        digestSeen = preferences[DIGEST_SEEN],
        newMailNotifications = preferences[NEW_MAIL_NOTIFICATIONS] ?: defaults.newMailNotifications,
        account = decodeAccount(preferences),
        push = decodePush(preferences),
    )
}

private fun decodePush(preferences: Preferences): PushSettings {
    val status = PushStatus.entries.firstOrNull { it.name == preferences[PUSH_STATUS] } ?: return PushSettings()
    val url = preferences[PUSH_URL]
    val p256dh = preferences[PUSH_P256DH]
    val auth = preferences[PUSH_AUTH]
    val subscription = if (url != null && p256dh != null && auth != null) PushSubscription(url, p256dh, auth) else null
    return PushSettings(status, subscription, preferences[PUSH_TOKEN])
}

private fun MutablePreferences.writePush(push: PushSettings) {
    this[PUSH_STATUS] = push.status.name
    push.streamToken?.let { this[PUSH_TOKEN] = it } ?: remove(PUSH_TOKEN)
    val subscription = push.subscription
    if (subscription == null) {
        remove(PUSH_URL)
        remove(PUSH_P256DH)
        remove(PUSH_AUTH)
    } else {
        this[PUSH_URL] = subscription.url
        this[PUSH_P256DH] = subscription.p256dh
        this[PUSH_AUTH] = subscription.auth
    }
}

private fun decodeAccount(preferences: Preferences): RememberedAccount? {
    val server = preferences[ACCOUNT_SERVER] ?: return null
    return RememberedAccount(server, preferences[ACCOUNT_EMAIL])
}
