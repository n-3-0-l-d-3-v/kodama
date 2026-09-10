package os.proximity.shared.mesh

import os.proximity.shared.files.FileDrop

/**
 * How [MeshManager] hands file transfer traffic to whatever owns file
 * state. Same seam as [ListSyncDelegate] and [CapabilityDelegate]: the mesh
 * does not know about storage, and storage does not know about the radio.
 */
interface FileTransferDelegate {

    /** A peer announced a file. Called only after policy allows or asks
     *  about it — never for an offer that was denied outright. */
    suspend fun onFileOffered(
        peerDeviceId: String,
        fileId: String,
        name: String,
        mimeType: String,
        sizeBytes: Long
    ): FileDrop

    /** The peer accepted our offer. Returns the bytes to send, or null if
     *  we no longer have them (e.g. the offer was abandoned locally). */
    suspend fun onOfferAccepted(fileId: String): ByteArray?

    suspend fun onOfferDeclined(fileId: String)

    suspend fun onFileDataReceived(fileId: String, bytes: ByteArray)

    suspend fun onFileSent(fileId: String)
}
