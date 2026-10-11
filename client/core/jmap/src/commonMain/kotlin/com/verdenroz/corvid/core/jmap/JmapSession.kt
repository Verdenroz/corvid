package com.verdenroz.corvid.core.jmap

/**
 * A JMAP session, rebased onto the host the caller actually dialled.
 * [accountId] is null until the caller is authenticated: an anonymous
 * session carries capabilities only, with no primary mail account.
 */
data class JmapSession(
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val eventSourceUrl: String,
    val accountId: String?,
    val state: String?,
    val maxCallsInRequest: Int?,
    val maxObjectsInGet: Int?,
    val maxSizeUpload: Long? = null,
)
