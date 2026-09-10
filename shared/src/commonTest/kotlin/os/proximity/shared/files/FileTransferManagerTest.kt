package os.proximity.shared.files

import kotlinx.coroutines.test.runTest
import os.proximity.shared.storage.InMemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTransferManagerTest {

    private var clock = 1_000L

    private fun manager(
        files: InMemoryFileStore = InMemoryFileStore(),
        ttlMillis: Long = 10_000L
    ) = FileTransferManager(FileDropStore(files), now = { clock }, ttlMillis = ttlMillis)

    // ------------------------------------------------------------ outbound

    @Test
    fun preparingAnOfferMakesItVisibleAsOffered() = runTest {
        val manager = manager()
        val drop = assertNotNull(manager.prepareOffer("peer-1", "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))

        assertEquals(FileTransferStatus.OFFERED, drop.status)
        assertEquals(FileTransferDirection.SENT, drop.direction)
        assertEquals(listOf(drop), manager.drops.value)
    }

    @Test
    fun aFileOverTheSizeLimitIsRejected() = runTest {
        val manager = manager()
        val tooBig = ByteArray((FileTransferLimits.MAX_FILE_BYTES + 1).toInt())

        assertNull(manager.prepareOffer("peer-1", "huge.bin", "application/octet-stream", tooBig))
        assertTrue(manager.drops.value.isEmpty(), "a rejected offer must not appear in the list")
    }

    @Test
    fun aFileExactlyAtTheLimitIsAccepted() = runTest {
        val manager = manager()
        val atLimit = ByteArray(FileTransferLimits.MAX_FILE_BYTES.toInt())
        assertNotNull(manager.prepareOffer("peer-1", "big.bin", "application/octet-stream", atLimit))
    }

    @Test
    fun acceptedOfferTransitionsToTransferringAndReturnsTheBytes() = runTest {
        val manager = manager()
        val bytes = byteArrayOf(9, 8, 7)
        val drop = assertNotNull(manager.prepareOffer("peer-1", "note.txt", "text/plain", bytes))

        val returned = assertNotNull(manager.onOfferAccepted(drop.id))
        assertTrue(bytes.contentEquals(returned))
        assertEquals(FileTransferStatus.TRANSFERRING, manager.drops.value.single().status)
    }

    @Test
    fun sendingCompletesTheTransferAndForgetsTheBytes() = runTest {
        val manager = manager()
        val drop = assertNotNull(manager.prepareOffer("peer-1", "note.txt", "text/plain", byteArrayOf(1)))
        manager.onOfferAccepted(drop.id)

        manager.onFileSent(drop.id)

        assertEquals(FileTransferStatus.COMPLETE, manager.drops.value.single().status)
        // The in-memory copy is gone; only a receiver's persisted copy should remain.
        assertNull(manager.bytesFor(drop.id))
    }

    @Test
    fun decliningAnOfferRemovesTheBytesWithoutPersistingThem() = runTest {
        val files = InMemoryFileStore()
        val manager = manager(files)
        val drop = assertNotNull(manager.prepareOffer("peer-1", "secret.txt", "text/plain", byteArrayOf(1, 2)))

        manager.onOfferDeclined(drop.id)

        assertEquals(FileTransferStatus.DECLINED, manager.drops.value.single().status)
        assertNull(manager.bytesFor(drop.id))
        // A declined offer must never have touched disk.
        assertEquals(0, files.sizeOf("file-${drop.id}.hex"))
    }

    @Test
    fun withdrawingAnOfferRemovesItEntirely() = runTest {
        val manager = manager()
        val drop = assertNotNull(manager.prepareOffer("peer-1", "note.txt", "text/plain", byteArrayOf(1)))

        manager.withdrawOffer(drop.id)

        assertTrue(manager.drops.value.isEmpty())
        assertNull(manager.bytesFor(drop.id))
    }

    // ------------------------------------------------------------- inbound

    @Test
    fun anOfferedFileArrivesAsPending() = runTest {
        val manager = manager()
        val drop = manager.onFileOffered("peer-1", "file-1", "report.pdf", "application/pdf", 4096)

        assertEquals(FileTransferStatus.PENDING, drop.status)
        assertEquals(FileTransferDirection.RECEIVED, drop.direction)
        assertEquals("report.pdf", manager.drops.value.single().name)
    }

    @Test
    fun receivedDataCompletesTheTransferAndPersistsTheBytes() = runTest {
        val files = InMemoryFileStore()
        val manager = manager(files)
        manager.onFileOffered("peer-1", "file-1", "report.pdf", "application/pdf", 3)

        manager.onFileDataReceived("file-1", byteArrayOf(1, 2, 3))

        assertEquals(FileTransferStatus.COMPLETE, manager.drops.value.single().status)
        val restored = assertNotNull(manager.bytesFor("file-1"))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(restored))
        assertTrue(files.sizeOf("file-file-1.hex") > 0)
    }

    // --------------------------------------------------------------- expiry

    @Test
    fun expiredTransfersAreRemovedOnLoad() = runTest {
        val files = InMemoryFileStore()
        val manager = manager(files, ttlMillis = 1_000L)
        val drop = assertNotNull(manager.prepareOffer("peer-1", "note.txt", "text/plain", byteArrayOf(1)))
        manager.onOfferAccepted(drop.id)
        manager.onFileSent(drop.id) // completes; now subject to expiry
        clock += 5_000

        val reloaded = manager(files, ttlMillis = 1_000L)
        reloaded.load()

        assertTrue(reloaded.drops.value.isEmpty())
    }

    @Test
    fun aTransferInFlightIsNeverTreatedAsExpired() = runTest {
        val manager = manager(ttlMillis = 1_000L)
        val drop = assertNotNull(manager.prepareOffer("peer-1", "note.txt", "text/plain", byteArrayOf(1)))
        manager.onOfferAccepted(drop.id) // TRANSFERRING

        clock += 5_000
        manager.purgeExpired()

        assertEquals(1, manager.drops.value.size, "an in-flight transfer must survive its own deadline")
    }

    @Test
    fun purgeExpiredLeavesLiveTransfersAlone() = runTest {
        val manager = manager(ttlMillis = 10_000L)
        val drop = assertNotNull(manager.prepareOffer("peer-1", "note.txt", "text/plain", byteArrayOf(1)))

        manager.purgeExpired()

        assertEquals(listOf(drop), manager.drops.value)
    }

    @Test
    fun manifestAndBytesSurviveAFullReload() = runTest {
        val files = InMemoryFileStore()
        val manager = manager(files)
        manager.onFileOffered("peer-1", "file-1", "photo.jpg", "image/jpeg", 3)
        manager.onFileDataReceived("file-1", byteArrayOf(5, 6, 7))

        val reloaded = manager(files)
        reloaded.load()

        val restoredDrop = reloaded.drops.value.single()
        assertEquals("photo.jpg", restoredDrop.name)
        assertEquals(FileTransferStatus.COMPLETE, restoredDrop.status)
        val restoredBytes = assertNotNull(reloaded.bytesFor("file-1"))
        assertTrue(byteArrayOf(5, 6, 7).contentEquals(restoredBytes))
    }
}
