package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.Serializable

internal val REQUIRED_CAPABILITIES = listOf(CORE_CAPABILITY, MAIL_CAPABILITY, SUBMISSION_CAPABILITY, FILENODE_CAPABILITY)

@Serializable
internal data class JmapRequest(
    val using: List<String>,
    val methodCalls: List<Invocation>,
)

@Serializable
internal data class JmapResponse(
    val methodResponses: List<Invocation>,
    val sessionState: String,
)
