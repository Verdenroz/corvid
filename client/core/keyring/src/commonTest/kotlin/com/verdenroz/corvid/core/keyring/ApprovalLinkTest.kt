package com.verdenroz.corvid.core.keyring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ApprovalLinkTest {
    @Test
    fun `a request's link parses back to its id and code`() {
        val request = DeviceRequest("0123456789abcdef0123456789abcdef", "042917", Instant.fromEpochSeconds(0))

        assertEquals("corvid-approve:0123456789abcdef0123456789abcdef?code=042917", request.approvalLink())
        assertEquals(ApprovalLink(request.id, request.code), parseApprovalLink(request.approvalLink()))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(ApprovalLink("abc", "123456"), parseApprovalLink("  corvid-approve:abc?code=123456\n"))
    }

    @Test
    fun `anything else is not a link`() {
        assertNull(parseApprovalLink("https://mail.example.com/approve?code=123456"))
        assertNull(parseApprovalLink("corvid-approve:abc?code=12345"))
        assertNull(parseApprovalLink("corvid-approve:?code=123456"))
        assertNull(parseApprovalLink("corvid-approve:abc?code=123456&host=example.com"))
    }
}
