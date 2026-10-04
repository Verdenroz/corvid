package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.Seal
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.mailbox.resources.Res
import com.verdenroz.corvid.feature.mailbox.resources.reader_address
import com.verdenroz.corvid.feature.mailbox.resources.reader_address_unverified
import kotlin.test.Test
import kotlin.test.assertEquals

class SendersTest {
    @Test
    fun `an address whose seal failed is qualified as not verified`() {
        assertEquals(
            uiText(Res.string.reader_address_unverified, "service@paypal.test"),
            senderAddress("service@paypal.test", Seal.Failed("paypal.test", "paypa1.test")),
        )
    }

    @Test
    fun `a signed or unsigned address is shown plainly`() {
        assertEquals(uiText(Res.string.reader_address, "ada@x.test"), senderAddress("ada@x.test", Seal.Signed("x.test")))
        assertEquals(uiText(Res.string.reader_address, "mum@x.test"), senderAddress("mum@x.test", Seal.Unsigned))
    }
}
