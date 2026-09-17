package os.proximity.shared.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import os.proximity.shared.mesh.CapabilityDelegate
import os.proximity.shared.storage.FileStore

/**
 * Tracks what this device offers and what nearby devices claim to offer.
 *
 * Two rules shape everything here:
 *
 * 1. **Only what the user enabled is advertised.** Nothing is offered on
 *    the user's behalf, ever — that is the "only advertise capabilities I
 *    explicitly enable" principle, and it is enforced at the point of
 *    building the advertisement rather than trusted to callers.
 * 2. **Everything a peer sends is filtered.** Unknown names are dropped so
 *    a peer cannot put arbitrary text in front of the user, and expired
 *    entries are never reported as live.
 */
class CapabilityRegistry(
    private val files: FileStore,
    private val now: () -> Long,
    private val advertisementLifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
    private val fileName: String = DEFAULT_FILE_NAME
) : CapabilityDelegate {

    private val mutex = Mutex()

    private val enabledState = MutableStateFlow(CapabilityCatalog.defaultEnabled)

    /** Capability names this device is willing to advertise. */
    val enabled: StateFlow<Set<String>> = enabledState.asStateFlow()

    private val peerState = MutableStateFlow<Map<String, List<Capability>>>(emptyMap())

    /**
     * Raw record of what each peer claimed, including entries that may have
     * since expired. Use [capabilitiesOf] for anything user-facing.
     */
    val peerCapabilities: StateFlow<Map<String, List<Capability>>> = peerState.asStateFlow()

    suspend fun load() = mutex.withLock {
        val raw = files.readText(fileName) ?: return@withLock
        enabledState.value = try {
            json.decodeFromString(SetSerializer(String.serializer()), raw)
                .filter { CapabilityCatalog.isKnown(it) }
                .toSet()
        } catch (e: Exception) {
            // Fall back to defaults rather than advertising nothing (which
            // would look like a broken app) or everything (which would
            // advertise things the user never chose).
            CapabilityCatalog.defaultEnabled
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) = mutex.withLock {
        if (!CapabilityCatalog.isKnown(name)) return@withLock
        enabledState.value = if (enabled) {
            enabledState.value + name
        } else {
            enabledState.value - name
        }
        files.writeText(
            fileName,
            json.encodeToString(SetSerializer(String.serializer()), enabledState.value)
        )
    }

    /**
     * Builds what to send a peer. Returns null when nothing is enabled —
     * there is no point announcing an empty offer, and staying silent is
     * the more private default.
     */
    override fun buildAdvertisement(): CapabilityAdvertisement? {
        val issuedAt = now()
        val capabilities = enabledState.value
            .filter { CapabilityCatalog.isKnown(it) }
            .sorted()
            .map {
                Capability(
                    name = it,
                    issuedAtEpochMillis = issuedAt,
                    expiresAtEpochMillis = issuedAt + advertisementLifetimeMillis
                )
            }
        return if (capabilities.isEmpty()) {
            null
        } else {
            CapabilityAdvertisement(capabilities, issuedAt)
        }
    }

    /** Records what a peer claims. Replaces any previous claim from them. */
    override fun onPeerAdvertisement(
        peerDeviceId: String,
        advertisement: CapabilityAdvertisement
    ) {
        val accepted = advertisement.capabilities
            // A peer controls these strings entirely; anything we do not
            // recognise is dropped rather than shown.
            .filter { CapabilityCatalog.isKnown(it.name) }
            // A peer cannot grant itself an unbounded advertisement by
            // claiming an expiry far in the future.
            .map { it.copy(expiresAtEpochMillis = minOf(it.expiresAtEpochMillis, now() + MAX_LIFETIME_MILLIS)) }
            .distinctBy { it.name }
            .take(CapabilityCatalog.definitions.size)

        peerState.value = peerState.value + (peerDeviceId to accepted)
    }

    override fun forgetPeer(peerDeviceId: String) {
        peerState.value = peerState.value - peerDeviceId
    }

    /** What this peer currently offers, expired entries excluded. */
    fun capabilitiesOf(peerDeviceId: String): List<Capability> {
        val current = now()
        return peerState.value[peerDeviceId].orEmpty().filter { it.isValidAt(current) }
    }

    fun peerOffers(peerDeviceId: String, capabilityName: String): Boolean =
        capabilitiesOf(peerDeviceId).any { it.name == capabilityName }

    companion object {
        const val DEFAULT_FILE_NAME = "capabilities.json"

        /** Long enough to be useful across a conversation, short enough that
         *  a device that leaves stops being advertised. */
        const val DEFAULT_LIFETIME_MILLIS = 15 * 60 * 1000L

        /** Ceiling applied to peer-supplied expiry times. */
        const val MAX_LIFETIME_MILLIS = 60 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true }
    }
}
