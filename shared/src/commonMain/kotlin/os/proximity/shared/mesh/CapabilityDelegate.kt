package os.proximity.shared.mesh

import os.proximity.shared.capability.CapabilityAdvertisement

/**
 * How [MeshManager] exchanges capability advertisements with whatever owns
 * capability state.
 *
 * The same seam as [ListSyncDelegate]: the mesh does not depend on storage,
 * and the registry does not depend on the radio.
 */
interface CapabilityDelegate {

    /** What to offer a peer, or null when the user has enabled nothing. */
    fun buildAdvertisement(): CapabilityAdvertisement?

    /** Records what a peer claims it currently offers. */
    fun onPeerAdvertisement(peerDeviceId: String, advertisement: CapabilityAdvertisement)

    /**
     * Discards a peer's claimed capabilities once its session ends. Not
     * strictly required for correctness — [buildAdvertisement] and reads of
     * a peer's capabilities already exclude expired entries — but without
     * it, every device ever connected to stays in memory as a (harmless but
     * pointless) stale entry for the life of the process.
     */
    fun forgetPeer(peerDeviceId: String)
}
