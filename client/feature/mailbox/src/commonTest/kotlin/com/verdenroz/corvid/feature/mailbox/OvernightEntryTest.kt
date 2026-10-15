package com.verdenroz.corvid.feature.mailbox

import com.verdenroz.corvid.core.model.Digest
import com.verdenroz.corvid.core.model.DigestGroup
import com.verdenroz.corvid.core.model.DigestItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private val AT = Instant.parse("2024-05-06T07:08:09Z")

private fun item(
    id: String,
    name: String? = null,
    from: String = "$id@x.test",
    list: Boolean = false,
) = DigestItem("t$id", id, from, "s", AT, null, name, list)

class OvernightEntryTest {
    @Test
    fun `the entry names each person once by first name, falls back to the address, and counts the lists`() {
        val digest =
            Digest(
                "d1",
                AT,
                AT,
                listOf(
                    DigestGroup("Work", listOf(item("a", "Ada Lovelace"), item("b", "Ada Lovelace"), item("c", from = "eve@x.test"))),
                    DigestGroup(null, listOf(item("l1", "Shop", list = true), item("l2", "Shop", list = true))),
                ),
            )

        assertEquals(OvernightEntry("d1", 5, listOf("Ada", "eve"), 2), digest.overnight())
    }
}
