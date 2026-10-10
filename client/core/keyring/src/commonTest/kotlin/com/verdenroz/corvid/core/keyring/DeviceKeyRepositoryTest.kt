package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeFileNode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val BASE_URL = "http://127.0.0.1:9999"

class DeviceKeyRepositoryTest {
    @Test
    fun `an unenrolled device has no key yet`() =
        runTest {
            val repository = repository(FakeJmapServer())

            assertIs<DeviceKeyLookup.Missing>(repository.fetch("laptop-1"))
        }

    @Test
    fun `an approved key is read through the record the server returns`() =
        runTest {
            val fake = FakeJmapServer()
            val approved = fake.devices.enroll("laptop-1")

            val found = assertIs<DeviceKeyLookup.Found>(repository(fake).fetch("laptop-1"))

            assertEquals(approved.toList(), found.key.toList())
        }

    @Test
    fun `a file planted under the device name in the mailbox account is never read`() =
        runTest {
            val fake = FakeJmapServer()
            fake.blobs["planted"] = byteArrayOf(1) + ByteArray(32) { 7 }
            fake.fileNodes += FakeFileNode("d1", null, "corvid-devices", "directory")
            fake.fileNodes += FakeFileNode("f1", "d1", "laptop-1.key", "file", "planted")

            assertIs<DeviceKeyLookup.Missing>(repository(fake).fetch("laptop-1"))
        }

    @Test
    fun `a revoked device reads as missing again`() =
        runTest {
            val fake = FakeJmapServer()
            fake.devices.enroll("laptop-1")
            fake.devices.revoke("laptop-1")

            assertIs<DeviceKeyLookup.Missing>(repository(fake).fetch("laptop-1"))
        }

    @Test
    fun `a server with device approval off reads as off, not missing`() =
        runTest {
            val fake = FakeJmapServer()
            fake.devices.enroll("laptop-1")
            fake.devices.off = true

            assertIs<DeviceKeyLookup.ApprovalOff>(repository(fake).fetch("laptop-1"))
        }

    @Test
    fun `a file of the wrong size is damaged, not missing`() =
        runTest {
            val fake = FakeJmapServer()
            fake.devices.enroll("laptop-1")
            fake.blobs[fake.devices.blobIdOf("laptop-1")] = ByteArray(10)

            assertIs<DeviceKeyLookup.Damaged>(repository(fake).fetch("laptop-1"))
        }

    @Test
    fun `a file with the wrong version byte is damaged`() =
        runTest {
            val fake = FakeJmapServer()
            fake.devices.enroll("laptop-1")
            fake.blobs[fake.devices.blobIdOf("laptop-1")] = ByteArray(33) { 9 }

            assertIs<DeviceKeyLookup.Damaged>(repository(fake).fetch("laptop-1"))
        }

    @Test
    fun `an unreachable lookup is not mistaken for a missing device key`() =
        runTest {
            val fake = FakeJmapServer()
            fake.devices.enroll("laptop-1")
            val repository = repository(fake)
            fake.unreachable = true

            assertIs<DeviceKeyLookup.Unreachable>(repository.fetch("laptop-1"))
        }

    private suspend fun repository(fake: FakeJmapServer): DeviceKeyRepository {
        val client = createJmapHttpClient(fake.engine)
        val jmapClient = JmapClient.connect(client, AuthLatch(), BASE_URL)
        return DeviceKeyRepository(jmapClient)
    }
}
