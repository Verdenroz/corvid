package com.verdenroz.corvid.core.model

/** Recipients are `email` or `Name <email>`; inReplyTo and references are Message-IDs, empty for a new message. */
data class Draft(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
)
