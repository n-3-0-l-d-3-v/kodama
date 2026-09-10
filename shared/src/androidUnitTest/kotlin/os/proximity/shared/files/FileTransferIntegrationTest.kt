package os.proximity.shared.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.GuardrailDecision
import os.proximity.shared.guardrail.InMemoryAuditLog
import os.proximity.shared.guardrail.PolicyRule
import os.proximity.shared.guardrail.TrustState
import os.proximity.shared.identity.DeviceIdentifiers
import os.proximity.shared.identity.DeviceIdentity
import os.proximity.shared.identity.DeviceIdentityProvider
import os.proximity.shared.identity.InMemoryTrustStore
import os.proximity.shared.identity.JcaSignatureVerifier
import os.proximity.shared.mesh.DiscoveredPeer
import os.proximity.shared.mesh.IncomingMessage
import os.proximity.shared.mesh.MeshEvent
import os.proximity.shared.mesh.MeshManager
import os.proximity.shared.mesh.MeshTransport
import os.proximity.shared.storage.InMemoryFileStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file offer travelling over the real mesh stack: policy, handshake,
 * encryption and chunking included, with only the radio replaced.
 *
 * [FileTransferManagerTest] covers the state machine in isolation; this
 * proves the offer/accept/decline/data envelopes actually survive the wire
 * between two independent [MeshManager]s and land somewhere useful.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferIntegrationTest {

    private val primitives = AndroidCryptoPrimitives()
    private val verifier = JcaSignatureVerifier()

    private class LoopbackTransport(private val selfAddress: String) : MeshTransport {
        var peer: LoopbackTransport? = null
        private val inbox = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 8192)
        private val peersState = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

        override val discoveredPeers: Flow<List<DiscoveredPeer>> = peersState
        override val incomingMessages: Flow<IncomingMessage> = inbox

        override fun startDiscovery() {
            peersState.value = listOf(DiscoveredPeer(peer!!.selfAddress, null, -50, 1_000))
        }

        override fun stopDiscovery() {
            peersState.value = emptyList()
        }

        override suspend fun connect(peerAddress: String): Boolean = true
        override fun disconnect(peerAddress: String) = Unit
        override fun maxPayloadSize(peerAddress: String): Int = 40 // force chunking

        override suspend fun send(peerAddress: String, payload: ByteArray): Boolean {
            val target = peer ?: return false
            target.inbox.emit(IncomingMessage(selfAddress, payload))
            return true
        }
    }

    private fun identityProvider(): DeviceIdentityProvider {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val identity = object : DeviceIdentity {
            override val publicKeyBytes: ByteArray = keyPair.public.encoded
            private val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            override val deviceId: String = DeviceIdentifiers.deviceIdFrom(hash)
            override val fingerprint: String = DeviceIdentifiers.fingerprintFrom(hash)
            override fun sign(data: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(data)
                    sign()
                }
        }
        return object : DeviceIdentityProvider {
            override suspend fun getOrCreateIdentity(): DeviceIdentity = identity
        }
    }

    private class Node(
        val manager: MeshManager,
        val transport: LoopbackTransport,
        val files: FileTransferManager,
        val engine: DefaultGuardrailEngine
    )

    private fun node(name: String, address: String, scope: CoroutineScope): Node {
        val transport = LoopbackTransport(address)
        val engine = DefaultGuardrailEngine(InMemoryAuditLog())
        val fileTransfer = FileTransferManager(FileDropStore(InMemoryFileStore()), now = { 1_000L })
        val manager = MeshManager(
            transport = transport,
            primitives = primitives,
            identityProvider = identityProvider(),
            verifier = verifier,
            guardrail = engine,
            trustStore = InMemoryTrustStore(),
            scope = scope,
            displayName = { name },
            fileTransfer = fileTransfer
        )
        return Node(manager, transport, fileTransfer, engine)
    }

    private fun allowConnect() = PolicyRule(
        id = "test-allow-connect",
        description = "Allow connections without prompting",
        priority = 100,
        matches = { it.actionType == ActionType.CONNECT_PEER },
        decide = { GuardrailDecision.Allow("Allowed by test policy.") }
    )

    private suspend fun pair(scope: CoroutineScope): Pair<Node, Node> {
        val alice = node("Alice", "AA", scope)
        val bob = node("Bob", "BB", scope)
        alice.transport.peer = bob.transport
        bob.transport.peer = alice.transport
        alice.engine.addRule(allowConnect())
        bob.engine.addRule(allowConnect())
        alice.manager.start()
        bob.manager.start()
        return alice to bob
    }

    private suspend fun connect(alice: Node, bob: Node): String {
        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
        return "BB"
    }

    @Test
    fun aFileOfferedToAnAllowingPeerArrivesAsPending() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        // Bob accepts every file offer outright, no prompt.
        bob.engine.addRule(
            PolicyRule(
                id = "allow-files",
                description = "Accept all files",
                priority = 100,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.Allow("Accepted by test policy.") }
            )
        )
        connect(alice, bob)
        advanceUntilIdle()
        val bobDeviceId = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId)

        val drop = assertNotNull(alice.files.prepareOffer(bobDeviceId, "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3, 4, 5)))
        alice.manager.offerFile(bobDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)
        advanceUntilIdle()

        val onBob = bob.files.drops.value.singleOrNull { it.id == drop.id }
        assertNotNull(onBob, "Bob never received the offer")
        assertEquals("photo.jpg", onBob.name)
    }

    @Test
    fun anAcceptedOfferDeliversTheExactBytes() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        bob.engine.addRule(
            PolicyRule(
                id = "allow-files",
                description = "Accept all files",
                priority = 100,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.Allow("Accepted by test policy.") }
            )
        )
        connect(alice, bob)
        advanceUntilIdle()
        val bobDeviceId = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId)

        val original = ByteArray(500) { it.toByte() } // multi-chunk at maxPayloadSize=40
        val drop = assertNotNull(alice.files.prepareOffer(bobDeviceId, "data.bin", "application/octet-stream", original))
        alice.manager.offerFile(bobDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)
        advanceUntilIdle()

        val received = assertNotNull(bob.files.bytesFor(drop.id))
        assertTrue(original.contentEquals(received), "received bytes must exactly match what was sent")
        assertEquals(FileTransferStatus.COMPLETE, bob.files.drops.value.single { it.id == drop.id }.status)
        assertEquals(FileTransferStatus.COMPLETE, alice.files.drops.value.single { it.id == drop.id }.status)
    }

    @Test
    fun anUnverifiedSenderIsAskedAndDeclineStopsTheBytes() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        // Bob runs the real default policy: RECEIVE_FILE with no matching
        // rule falls to default-deny (no FILES_FROM_VERIFIED_ONLY rule is
        // registered here, so this exercises the plain default, not the
        // app's usual policy — see the next test for that).
        connect(alice, bob)
        advanceUntilIdle()
        val bobDeviceId = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId)

        val drop = assertNotNull(alice.files.prepareOffer(bobDeviceId, "note.txt", "text/plain", byteArrayOf(1)))
        alice.manager.offerFile(bobDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)
        advanceUntilIdle()

        // Default-deny: never even reaches Bob's file list.
        assertTrue(bob.files.drops.value.isEmpty())
        assertNull(bob.files.bytesFor(drop.id))
    }

    @Test
    fun askUserFlowRequiresApprovalBeforeBytesAreSent() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        bob.engine.addRule(
            PolicyRule(
                id = "ask-for-files",
                description = "Ask before accepting a file",
                priority = 100,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.AskUser("Accept this file?") }
            )
        )
        val bobEvents = mutableListOf<MeshEvent>()
        backgroundScope.launch { bob.manager.events.collect { bobEvents.add(it) } }

        connect(alice, bob)
        advanceUntilIdle()
        val bobDeviceId = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId)

        val drop = assertNotNull(alice.files.prepareOffer(bobDeviceId, "note.txt", "text/plain", byteArrayOf(42)))
        alice.manager.offerFile(bobDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)
        advanceUntilIdle()

        // Visible as PENDING while Bob has not yet answered.
        assertEquals(FileTransferStatus.PENDING, bob.files.drops.value.single { it.id == drop.id }.status)
        assertNull(bob.files.bytesFor(drop.id))

        val prompt = assertNotNull(bobEvents.filterIsInstance<MeshEvent.DecisionRequired>().firstOrNull())
        assertEquals(ActionType.RECEIVE_FILE, prompt.actionType)

        bob.manager.resolveDecision(prompt.decisionId, allow = true)
        advanceUntilIdle()

        val received = assertNotNull(bob.files.bytesFor(drop.id))
        assertTrue(byteArrayOf(42).contentEquals(received))
    }

    @Test
    fun decliningAnAskedOfferNeverDeliversBytes() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        bob.engine.addRule(
            PolicyRule(
                id = "ask-for-files",
                description = "Ask before accepting a file",
                priority = 100,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.AskUser("Accept this file?") }
            )
        )
        val bobEvents = mutableListOf<MeshEvent>()
        backgroundScope.launch { bob.manager.events.collect { bobEvents.add(it) } }

        connect(alice, bob)
        advanceUntilIdle()
        val bobDeviceId = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId)

        val drop = assertNotNull(alice.files.prepareOffer(bobDeviceId, "note.txt", "text/plain", byteArrayOf(42)))
        alice.manager.offerFile(bobDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)
        advanceUntilIdle()

        val prompt = assertNotNull(bobEvents.filterIsInstance<MeshEvent.DecisionRequired>().firstOrNull())
        bob.manager.resolveDecision(prompt.decisionId, allow = false)
        advanceUntilIdle()

        assertNull(bob.files.bytesFor(drop.id))
        assertEquals(FileTransferStatus.DECLINED, bob.files.drops.value.single { it.id == drop.id }.status)
        // The sender learns about the decline too, rather than waiting forever.
        assertEquals(FileTransferStatus.DECLINED, alice.files.drops.value.single { it.id == drop.id }.status)
    }

    @Test
    fun sendingAFileIsAllowedByDefaultRegardlessOfTrust() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        bob.engine.addRule(
            PolicyRule(
                id = "allow-files",
                description = "Accept all files",
                priority = 100,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.Allow("Accepted by test policy.") }
            )
        )
        connect(alice, bob)
        advanceUntilIdle()
        val bobDeviceId = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId)

        // Alice never verified Bob; sending is still her own choice to make.
        assertEquals(TrustState.UNVERIFIED, alice.manager.peers.value.first { it.deviceId == bobDeviceId }.trustState)

        val drop = assertNotNull(alice.files.prepareOffer(bobDeviceId, "note.txt", "text/plain", byteArrayOf(1)))
        val offered = alice.manager.offerFile(bobDeviceId, drop.id, drop.name, drop.mimeType, drop.sizeBytes)

        assertTrue(offered, "SEND_FILE must be allowed by default; trust gating belongs to the receiver")
    }
}
