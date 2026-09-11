package os.proximity.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import os.proximity.android.data.AppSettings
import os.proximity.shared.capability.Capability
import os.proximity.shared.capability.CapabilityRegistry
import os.proximity.shared.crypto.CryptoPrimitives
import os.proximity.shared.domain.Conversation
import os.proximity.shared.domain.ConversationStore
import os.proximity.shared.domain.Peer
import os.proximity.shared.files.FileDrop
import os.proximity.shared.files.FileTransferManager
import os.proximity.shared.guardrail.AuditLog
import os.proximity.shared.guardrail.AuditLogEntry
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.PolicyCatalog
import os.proximity.shared.identity.DeviceIdentifiers
import os.proximity.shared.identity.DeviceIdentityProvider
import os.proximity.shared.identity.QrVerificationCodec
import os.proximity.shared.lists.SharedList
import os.proximity.shared.lists.SharedListRepository
import os.proximity.shared.mesh.MeshEvent
import os.proximity.shared.mesh.MeshManager
import os.proximity.shared.status.StatusBoardManager
import os.proximity.shared.status.StatusUpdate

/** Result of scanning someone's verification QR code. */
sealed class QrScanOutcome {
    data class Verified(val deviceId: String) : QrScanOutcome()

    /** The scanned code belongs to a different device than the one expected. */
    data class Mismatch(val scannedDeviceId: String) : QrScanOutcome()

    /** Not a Kodama code at all — a poster, a URL, someone else's app. */
    object Invalid : QrScanOutcome()
}

/**
 * Holds screen state and owns the lifetime of mesh work.
 *
 * Deliberately thin: policy lives in the Guardrail Engine and protocol
 * lives in `shared`, so this class only translates between those and
 * Compose state.
 */
class ProximityViewModel(
    private val settings: AppSettings,
    private val engine: DefaultGuardrailEngine,
    private val auditLog: AuditLog,
    private val identityProvider: DeviceIdentityProvider,
    private val listRepository: SharedListRepository,
    private val conversationStore: ConversationStore,
    private val capabilities: CapabilityRegistry,
    private val cryptoPrimitives: CryptoPrimitives,
    private val fileTransfer: FileTransferManager,
    private val statusBoard: StatusBoardManager,
    val mesh: MeshManager
) : ViewModel() {

    val peers: StateFlow<List<Peer>> = mesh.peers
    val conversations: StateFlow<Map<String, Conversation>> = mesh.conversations
    val isScanning: StateFlow<Boolean> = mesh.isScanning
    val auditEntries: StateFlow<List<AuditLogEntry>> = auditLog.entries
    val displayName: StateFlow<String> = settings.displayName
    val hasOnboarded: StateFlow<Boolean> = settings.hasOnboarded
    val enabledPolicyIds: StateFlow<Set<String>> = settings.enabledPolicyIds
    val lists: StateFlow<Map<String, SharedList>> = listRepository.lists
    val enabledCapabilities: StateFlow<Set<String>> = capabilities.enabled
    val runInBackground: StateFlow<Boolean> = settings.runInBackground
    val peerCapabilities: StateFlow<Map<String, List<Capability>>> = capabilities.peerCapabilities
    val fileDrops: StateFlow<List<FileDrop>> = fileTransfer.drops
    val statusBoardEntries: StateFlow<Map<String, StatusUpdate>> = statusBoard.board

    /** A Guardrail "ask me" decision currently blocking the mesh. */
    var pendingDecision by mutableStateOf<MeshEvent.DecisionRequired?>(null)
        private set

    /** Transient message shown to the user (a block, or a status change). */
    var banner by mutableStateOf<String?>(null)
        private set

    /** This device's own verification code, for showing to another person. */
    var myFingerprint by mutableStateOf<String?>(null)
        private set

    /** This device's own QR payload — same identity as [myFingerprint], scannable. */
    var myQrPayload by mutableStateOf<String?>(null)
        private set

    init {
        mesh.start()
        applyEnabledPolicies()

        viewModelScope.launch {
            mesh.restoreConversations(conversationStore.load())
            // Persist afterwards, so the restore itself does not race with a
            // save of the still-empty in-memory state.
            mesh.conversations.collect { conversationStore.save(it) }
        }

        viewModelScope.launch {
            val identity = runCatching { identityProvider.getOrCreateIdentity() }.getOrNull()
            myFingerprint = identity?.fingerprint
            myQrPayload = identity?.publicKeyBytes?.let { QrVerificationCodec.encode(it) }
        }

        viewModelScope.launch {
            mesh.events.collect { event ->
                when (event) {
                    is MeshEvent.DecisionRequired -> pendingDecision = event
                    is MeshEvent.Blocked -> banner = event.reason
                    is MeshEvent.Notice -> banner = event.text
                }
            }
        }

        viewModelScope.launch {
            settings.enabledPolicyIds.collect { applyEnabledPolicies(it) }
        }

        // The board only filters expiry on read (currentStatus), so this
        // ticker exists purely to make staleness show up in the UI without
        // the user having to interact with anything first.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                statusBoard.purgeExpired()
            }
        }
    }

    // ------------------------------------------------------------- discovery

    fun toggleScanning() {
        viewModelScope.launch {
            if (isScanning.value) mesh.stopDiscovery() else mesh.startDiscovery()
        }
    }

    fun connectTo(peer: Peer) {
        viewModelScope.launch { mesh.connectTo(peer.transportAddress) }
    }

    fun disconnect(peer: Peer) = mesh.disconnect(peer.transportAddress)

    // ------------------------------------------------------------- decisions

    fun resolvePendingDecision(allow: Boolean) {
        val decision = pendingDecision ?: return
        pendingDecision = null
        viewModelScope.launch { mesh.resolveDecision(decision.decisionId, allow) }
    }

    fun dismissBanner() {
        banner = null
    }

    // ----------------------------------------------------------------- chat

    fun sendMessage(peerDeviceId: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { mesh.sendChat(peerDeviceId, trimmed) }
    }

    // ----------------------------------------------------------------- files

    /**
     * Prepares and offers a file. Returns false if it was too large to send
     * (bytes never leave the device in that case) or if the offer itself
     * was blocked by policy.
     */
    fun sendFile(peerDeviceId: String, name: String, mimeType: String, bytes: ByteArray) {
        viewModelScope.launch {
            val drop = fileTransfer.prepareOffer(peerDeviceId, name, mimeType, bytes)
            if (drop == null) {
                banner = "\"$name\" is too large to send."
                return@launch
            }
            mesh.offerFile(peerDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)
        }
    }

    /** Bytes for a completed transfer, for the caller to write wherever the user chose. */
    suspend fun bytesForFile(fileId: String): ByteArray? = fileTransfer.bytesFor(fileId)

    fun fileDropsFor(peerDeviceId: String): List<FileDrop> =
        fileDrops.value.filter { it.peerDeviceId == peerDeviceId }

    // ---------------------------------------------------------------- lists

    fun createList(name: String) {
        viewModelScope.launch { listRepository.createList(name) }
    }

    fun addListItem(listId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { broadcast(listRepository.addItem(listId, text)) }
    }

    fun setListItemDone(listId: String, itemId: String, done: Boolean) {
        viewModelScope.launch { broadcast(listRepository.setItemDone(listId, itemId, done)) }
    }

    fun removeListItem(listId: String, itemId: String) {
        viewModelScope.launch { broadcast(listRepository.removeItem(listId, itemId)) }
    }

    /**
     * Local state is already updated by the repository; this only tells
     * peers. A failure to reach anyone is not an error — the change is kept
     * and reconciled the next time we connect.
     */
    private suspend fun broadcast(operation: os.proximity.shared.lists.ListOperation?) {
        if (operation != null) mesh.broadcastListOperation(operation)
    }

    // ---------------------------------------------------------------- trust

    fun markVerified(deviceId: String) {
        viewModelScope.launch { mesh.markVerified(deviceId) }
    }

    fun revokeVerification(deviceId: String) {
        viewModelScope.launch { mesh.revokeVerification(deviceId) }
    }

    /**
     * Handles a decoded QR scan result.
     *
     * When [expectedDeviceId] is given (scanning from an open conversation),
     * the scanned key must match it exactly before anything is trusted — a
     * mismatch is reported, never silently verified against the wrong
     * identity. When null (scanning a stranger's code before ever
     * connecting), whoever the key belongs to is trusted directly: the
     * in-person scan itself is the verification, per
     * docs/THREAT_MODEL.md #9.
     */
    fun handleScannedCode(payload: String, expectedDeviceId: String? = null): QrScanOutcome {
        val publicKey = QrVerificationCodec.decode(payload) ?: return QrScanOutcome.Invalid
        val scannedDeviceId = DeviceIdentifiers.deviceIdFrom(cryptoPrimitives.sha256(publicKey))

        if (expectedDeviceId != null && scannedDeviceId != expectedDeviceId) {
            return QrScanOutcome.Mismatch(scannedDeviceId)
        }

        markVerified(scannedDeviceId)
        return QrScanOutcome.Verified(scannedDeviceId)
    }

    fun reportScanOutcome(outcome: QrScanOutcome) {
        banner = when (outcome) {
            is QrScanOutcome.Verified -> "Verified via QR code."
            is QrScanOutcome.Mismatch ->
                "That code doesn't match this conversation — did not verify. " +
                    "Make sure you're scanning the right person's screen."
            QrScanOutcome.Invalid -> "That wasn't a Kodama verification code."
        }
    }

    // --------------------------------------------------------------- status

    fun postStatus(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { mesh.broadcastStatus(trimmed) }
    }

    fun statusOf(peerDeviceId: String): StatusUpdate? = statusBoard.currentStatus(peerDeviceId)

    // ----------------------------------------------------------- capabilities

    fun setCapabilityEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch { capabilities.setEnabled(name, enabled) }
    }

    /** What a peer currently offers, expired claims excluded. */
    fun capabilitiesOf(deviceId: String): List<Capability> = capabilities.capabilitiesOf(deviceId)

    // -------------------------------------------------------------- settings

    fun setDisplayName(name: String) = settings.setDisplayName(name)

    fun setRunInBackground(value: Boolean) = settings.setRunInBackground(value)

    fun completeOnboarding() = settings.setOnboarded(true)

    fun setPolicyEnabled(id: String, enabled: Boolean) = settings.setPolicyEnabled(id, enabled)

    private fun applyEnabledPolicies(ids: Set<String> = settings.enabledPolicyIds.value) {
        viewModelScope.launch {
            // Rebuild from scratch rather than diffing: the rule set is small,
            // and a stale rule left behind would be a silent policy hole.
            PolicyCatalog.options.forEach { engine.removeRule(it.id) }
            PolicyCatalog.options
                .filter { it.id in ids }
                .forEach { engine.addRule(it.buildRule()) }
        }
    }

    class Factory(
        private val settings: AppSettings,
        private val engine: DefaultGuardrailEngine,
        private val auditLog: AuditLog,
        private val identityProvider: DeviceIdentityProvider,
        private val listRepository: SharedListRepository,
        private val conversationStore: ConversationStore,
        private val capabilities: CapabilityRegistry,
        private val cryptoPrimitives: CryptoPrimitives,
        private val fileTransfer: FileTransferManager,
        private val statusBoard: StatusBoardManager,
        private val mesh: MeshManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProximityViewModel(
                settings, engine, auditLog, identityProvider, listRepository,
                conversationStore, capabilities, cryptoPrimitives, fileTransfer, statusBoard, mesh
            ) as T
    }
}
