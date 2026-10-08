package com.verdenroz.corvid.core.keyring

/** What a new device's QR code carries. It names no server: the approving device uses its own. */
data class ApprovalLink(val id: String, val code: String)

private val LINK = Regex("corvid-approve:([0-9a-zA-Z]+)\\?code=([0-9]{6})")

fun DeviceRequest.approvalLink(): String = "corvid-approve:$id?code=$code"

fun parseApprovalLink(text: String): ApprovalLink? =
    LINK.matchEntire(text.trim())?.let { ApprovalLink(it.groupValues[1], it.groupValues[2]) }
