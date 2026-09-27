package com.verdenroz.corvid.core.keyring

import com.verdenroz.corvid.core.jmap.AuthLatch
import com.verdenroz.corvid.core.jmap.JmapClient
import com.verdenroz.corvid.core.jmap.createJmapHttpClient
import com.verdenroz.corvid.core.testing.FakeFileNode
import com.verdenroz.corvid.core.testing.FakeJmapServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

private const val BASE_URL = "http://127.0.0.1:9999"

class DeviceKeyRepositoryTest {
    @Test
    fun `an unenrolled device has no key yet`() =
        runTest {
            val repository = repository(FakeJmapServer())

            assertIs<DeviceKeyLookup.Missing>(repository.fetch("laptop-1"))
        }

    @Test
    fun `enrollment creates a key that fetch then finds`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)

            val enrolled = repository.enroll("laptop-1")
            val fetched = repository.fetch("laptop-1")

            assertEquals(32, enrolled.size)
            val found = assertIs<DeviceKeyLookup.Found>(fetched)
            assertEquals(enrolled.toList(), found.key.toList())
        }

    @Test
    fun `re-enrolling never adopts the old file, it destroys and recreates`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)

            val first = repository.enroll("laptop-1")
            val second = repository.enroll("laptop-1")

            val devicesFolderChildren = fake.fileNodes.filter { it.parentId != null }
            assertEquals(1, devicesFolderChildren.size)
            assertNotEquals(first.toList(), second.toList())
        }

    @Test
    fun `a file of the wrong size is damaged, not silently replaced`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            repository.enroll("laptop-1")
            val fileNode = fake.fileNodes.first { it.name == "laptop-1.key" }
            fake.blobs[fileNode.blobId!!] = ByteArray(10)

            assertIs<DeviceKeyLookup.Damaged>(repository.fetch("laptop-1"))
            assertEquals(1, fake.fileNodes.count { it.name == "laptop-1.key" })
        }

    @Test
    fun `a file with the wrong version byte is damaged`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            repository.enroll("laptop-1")
            val fileNode = fake.fileNodes.first { it.name == "laptop-1.key" }
            fake.blobs[fileNode.blobId!!] = ByteArray(33) { 9 }

            assertIs<DeviceKeyLookup.Damaged>(repository.fetch("laptop-1"))
        }

    @Test
    fun `an unreachable lookup is not mistaken for a missing device key`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            repository.enroll("laptop-1")
            fake.unreachable = true

            assertIs<DeviceKeyLookup.Unreachable>(repository.fetch("laptop-1"))
        }

    @Test
    fun `a refused destroy of the old key fails enrollment instead of leaving two files`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            repository.enroll("laptop-1")
            val fileNode = fake.fileNodes.first { it.name == "laptop-1.key" }
            fake.refuseDestroy += fileNode.id

            assertFailsWith<DeviceKeyConflict.DestroyFailed> { repository.enroll("laptop-1") }
            assertEquals(1, fake.fileNodes.count { it.name == "laptop-1.key" })
        }

    @Test
    fun `a refused create fails typed rather than a bare lookup exception`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            fake.refuseCreate = true

            assertFailsWith<DeviceKeyConflict.CreateFailed> { repository.enroll("laptop-1") }
        }

    @Test
    fun `two root corvid-devices folders read as ambiguous, never as a guess`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            fake.fileNodes += FakeFileNode("d1", null, "corvid-devices", "directory")
            fake.fileNodes += FakeFileNode("d2", null, "corvid-devices", "directory")

            assertIs<DeviceKeyLookup.Ambiguous>(repository.fetch("laptop-1"))
        }

    @Test
    fun `enrolling into an ambiguous folder set fails typed instead of picking one`() =
        runTest {
            val fake = FakeJmapServer()
            val repository = repository(fake)
            fake.fileNodes += FakeFileNode("d1", null, "corvid-devices", "directory")
            fake.fileNodes += FakeFileNode("d2", null, "corvid-devices", "directory")

            assertFailsWith<DeviceKeyConflict.AmbiguousRootFolder> { repository.enroll("laptop-1") }
        }

    private suspend fun repository(fake: FakeJmapServer): DeviceKeyRepository {
        val client = createJmapHttpClient(fake.engine)
        val jmapClient = JmapClient.connect(client, AuthLatch(), BASE_URL)
        return DeviceKeyRepository(jmapClient)
    }
}
