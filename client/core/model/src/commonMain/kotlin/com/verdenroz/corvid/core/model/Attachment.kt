package com.verdenroz.corvid.core.model

/**
 * One file a message carries, held on the server as [blobId]. [name] is null when the sender gave
 * none. [size] is the decoded size in bytes, as the server reports it.
 */
data class Attachment(
    val blobId: String,
    val name: String?,
    val type: String,
    val size: Long,
)
