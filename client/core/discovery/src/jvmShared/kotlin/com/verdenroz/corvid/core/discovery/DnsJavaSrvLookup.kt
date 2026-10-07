package com.verdenroz.corvid.core.discovery

import com.verdenroz.corvid.core.domain.SrvLookup
import com.verdenroz.corvid.core.domain.SrvTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.Lookup
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Type

class DnsJavaSrvLookup : SrvLookup {
    override suspend fun lookup(name: String): SrvTarget? =
        withContext(Dispatchers.IO) {
            preferred(Lookup(name, Type.SRV).run().orEmpty().filterIsInstance<SRVRecord>())
        }
}

internal fun preferred(records: List<SRVRecord>): SrvTarget? =
    records
        .minWithOrNull(compareBy<SRVRecord> { it.priority }.thenByDescending { it.weight })
        ?.let { SrvTarget(it.target.toString(true), it.port) }
