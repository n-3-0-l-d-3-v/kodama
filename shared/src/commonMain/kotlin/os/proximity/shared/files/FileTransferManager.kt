package os.proximity.shared.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.proximity.shared.mesh.FileTransferDelegate

/**
 * Owns this device's file transfers: the ones it is sending and the ones
 * it has been offered.
 *
 * A local edit (preparing an offer) and a remote event (a peer's response)
 * both flow through the same state, but only one path writes bytes to
 * disk: an outbound file's bytes stay in memory until the transfer either
 * completes or is abandoned, so a declined offer never touches storage.
 */
class FileTransferManager(
    private val store: FileDropStore,
    private val now: () -> Long,
    private val ttlMillis: Long = FileTransferLimits.DEFAULT_TTL_MILLIS
) : FileTransferDelegate {

    private val mutex = Mutex()
    private val dropsState = MutableStateFlow<List<FileDrop>>(emptyList())
    val drops: StateFlow<List<FileDrop>> = dropsState.asStateFlow()

    private val pendingOutboundBytes = mutableMapOf<String, ByteArray>()
    private var idCounter = 0

    /** Reads the manifest and purges anything already expired. Call once at startup. */
    suspend fun load() = mutex.withLock {
        val loaded = store.loadManifest()
        val nowMillis = now()
        val (expired, live) = loaded.partition { it.isExpired(nowMillis) }
        for (drop in expired) store.deleteBytes(drop.id)
        dropsState.value = live
        if (expired.isNotEmpty()) store.saveManifest(live)
    }

    /** Deletes anything that has expired since [load]. Safe to call opportunistically. */
    suspend fun purgeExpired() = mutex.withLock {
        val nowMillis = now()
        val (expired, live) = dropsState.value.partition { it.isExpired(nowMillis) }
        if (expired.isEmpty()) return@withLock
        for (drop in expired) {
            store.deleteBytes(drop.id)
            pendingOutboundBytes.remove(drop.id)
        }
        dropsState.value = live
        store.saveManifest(live)
    }

    /** Prepares an outbound offer. Returns null if [bytes] exceeds the size limit. */
    suspend fun prepareOffer(
        peerDeviceId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray
    ): FileDrop? {
        if (bytes.size > FileTransferLimits.MAX_FILE_BYTES) return null

        val drop = FileDrop(
            id = newId(),
            peerDeviceId = peerDeviceId,
            direction = FileTransferDirection.SENT,
            name = name,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            status = FileTransferStatus.OFFERED,
            createdAtEpochMillis = now(),
            expiresAtEpochMillis = now() + ttlMillis
        )
        mutex.withLock {
            pendingOutboundBytes[drop.id] = bytes
            dropsState.value = dropsState.value + drop
        }
        return drop
    }

    /** Withdraws an outbound offer that was never accepted, e.g. the user changed their mind. */
    suspend fun withdrawOffer(fileId: String) = mutex.withLock {
        pendingOutboundBytes.remove(fileId)
        dropsState.value = dropsState.value.filterNot { it.id == fileId }
    }

    /** Bytes for a completed or in-flight transfer, wherever they currently live. */
    suspend fun bytesFor(fileId: String): ByteArray? =
        pendingOutboundBytes[fileId] ?: store.loadBytes(fileId)

    // -------------------------------------------------------- FileTransferDelegate

    override suspend fun onFileOffered(
        peerDeviceId: String,
        fileId: String,
        name: String,
        mimeType: String,
        sizeBytes: Long
    ): FileDrop {
        val drop = FileDrop(
            id = fileId,
            peerDeviceId = peerDeviceId,
            direction = FileTransferDirection.RECEIVED,
            name = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            status = FileTransferStatus.PENDING,
            createdAtEpochMillis = now(),
            expiresAtEpochMillis = now() + ttlMillis
        )
        mutex.withLock {
            dropsState.value = dropsState.value + drop
            store.saveManifest(dropsState.value)
        }
        return drop
    }

    override suspend fun onOfferAccepted(fileId: String): ByteArray? = mutex.withLock {
        val bytes = pendingOutboundBytes[fileId] ?: return@withLock null
        setStatusLocked(fileId, FileTransferStatus.TRANSFERRING)
        bytes
    }

    override suspend fun onOfferDeclined(fileId: String) = mutex.withLock {
        pendingOutboundBytes.remove(fileId)
        setStatusLocked(fileId, FileTransferStatus.DECLINED)
    }

    override suspend fun onFileDataReceived(fileId: String, bytes: ByteArray) {
        store.saveBytes(fileId, bytes)
        mutex.withLock { setStatusLocked(fileId, FileTransferStatus.COMPLETE) }
    }

    override suspend fun onFileSent(fileId: String) = mutex.withLock {
        pendingOutboundBytes.remove(fileId)
        setStatusLocked(fileId, FileTransferStatus.COMPLETE)
    }

    // --------------------------------------------------------------- helpers

    /** Caller must already hold [mutex]. */
    private suspend fun setStatusLocked(fileId: String, status: FileTransferStatus) {
        if (dropsState.value.none { it.id == fileId }) return
        dropsState.value = dropsState.value.map { if (it.id == fileId) it.copy(status = status) else it }
        store.saveManifest(dropsState.value)
    }

    private fun newId(): String = "${now().toString(36)}-${(idCounter++).toString(36)}"
}
