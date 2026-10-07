package com.verdenroz.corvid.core.discovery

import com.verdenroz.corvid.core.domain.SrvTarget
import org.xbill.DNS.DClass
import org.xbill.DNS.Name
import org.xbill.DNS.SRVRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun srv(
    priority: Int,
    weight: Int,
    port: Int,
    target: String,
) = SRVRecord(Name.fromString("_corvid._tcp.example.com."), DClass.IN, 300, priority, weight, port, Name.fromString(target))

class DnsJavaSrvLookupTest {
    @Test
    fun `the lowest priority wins and the target loses its trailing dot`() {
        val records = listOf(srv(10, 5, 4443, "backup.example.com."), srv(0, 5, 4443, "mail.example.com."))

        assertEquals(SrvTarget("mail.example.com", 4443), preferred(records))
    }

    @Test
    fun `the heaviest of equal priorities wins`() {
        val records = listOf(srv(0, 1, 4443, "light.example.com."), srv(0, 9, 8443, "heavy.example.com."))

        assertEquals(SrvTarget("heavy.example.com", 8443), preferred(records))
    }

    @Test
    fun `no records is no target`() {
        assertNull(preferred(emptyList()))
    }
}
