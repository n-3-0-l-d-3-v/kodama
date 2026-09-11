package os.proximity.shared.mesh

/**
 * How [MeshManager] hands a peer's status broadcast to whatever owns the
 * status board. Same seam as [ListSyncDelegate], [CapabilityDelegate], and
 * [FileTransferDelegate]: the mesh does not know about presentation, and
 * the board does not know about the radio.
 */
interface StatusBoardDelegate {
    fun onStatusReceived(
        peerDeviceId: String,
        text: String,
        postedAtEpochMillis: Long,
        expiresAtEpochMillis: Long
    )
}
