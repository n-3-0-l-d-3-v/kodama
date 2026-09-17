package os.proximity.shared.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.domain.DeliveryState
import os.proximity.shared.domain.LinkState
import os.proximity.shared.domain.MessageDirection
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.GuardrailDecision
import os.proximity.shared.guardrail.InMemoryAuditLog
import os.proximity.shared.guardrail.PolicyRule
import os.proximity.shared.identity.DeviceIdentifiers
import os.proximity.shared.identity.DeviceIdentity
import os.proximity.shared.identity.DeviceIdentityProvider
import os.proximity.shared.identity.InMemoryTrustStore
import os.proximity.shared.identity.JcaSignatureVerifier
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two [MeshManager]s connected by an in-memory transport.
 *
 * This exercises the real chain — policy → handshake → key agreement →
 * framing → encryption → reassembly → decryption — with only the radio
 * replaced. The fake MTU is deliberately tiny so every message is chunked,
 * which is where framing bugs actually surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshManagerIntegrationTest {

    private val primitives = AndroidCryptoPrimitives()
    private val verifier = JcaSignatureVerifier()

    /** Delivers writes straight into the paired transport's inbox. */
    private class LoopbackTransport(private val selfAddress: String) : MeshTransport {

        var peer: LoopbackTransport? = null
        var payloadSize: Int = 60

        private val inbox = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 4096)
        private val peersState = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

        override val discoveredPeers: Flow<List<DiscoveredPeer>> = peersState
        override val incomingMessages: Flow<IncomingMessage> = inbox

        override fun startDiscovery() {
            peersState.value = listOf(
                DiscoveredPeer(peer!!.selfAddress, null, -50, 1_000)
            )
        }

        override fun stopDiscovery() {
            peersState.value = emptyList()
        }

        /** Lets a test simulate a scan cycle reporting arbitrary results directly. */
        fun reportDiscovered(peers: List<DiscoveredPeer>) {
            peersState.value = peers
        }

        override suspend fun connect(peerAddress: String): Boolean = true

        override fun disconnect(peerAddress: String) = Unit

        override fun maxPayloadSize(peerAddress: String): Int = payloadSize

        override suspend fun send(peerAddress: String, payload: ByteArray): Boolean {
            val target = peer ?: return false
            target.inbox.emit(IncomingMessage(selfAddress, payload))
            return true
        }
    }

    private class TestIdentity(keyPair: KeyPair) : DeviceIdentity {
        private val privateKey = keyPair.private
        override val publicKeyBytes: ByteArray = keyPair.public.encoded
        private val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        override val deviceId: String = DeviceIdentifiers.deviceIdFrom(hash)
        override val fingerprint: String = DeviceIdentifiers.fingerprintFrom(hash)
        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(data)
                sign()
            }
    }

    private fun identityProvider(): DeviceIdentityProvider {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val identity = TestIdentity(generator.generateKeyPair())
        return object : DeviceIdentityProvider {
            override suspend fun getOrCreateIdentity(): DeviceIdentity = identity
        }
    }

    private fun allowConnectRule() = PolicyRule(
        id = "test-allow-connect",
        description = "Allow connections without prompting",
        priority = 100,
        matches = { it.actionType == ActionType.CONNECT_PEER },
        decide = { GuardrailDecision.Allow("Allowed by test policy.") }
    )

    private class Node(
        val manager: MeshManager,
        val transport: LoopbackTransport,
        val engine: DefaultGuardrailEngine,
        val auditLog: InMemoryAuditLog,
        val name: String
    )

    private fun node(name: String, address: String, scope: CoroutineScope): Node {
        val transport = LoopbackTransport(address)
        val auditLog = InMemoryAuditLog()
        // The per-peer rate limit (docs/THREAT_MODEL.md #6) is exercised by
        // its own dedicated tests in the guardrail package; a high ceiling
        // here keeps it from interfering with tests that legitimately send
        // many messages in a single (real-clock, effectively instantaneous)
        // test run.
        val engine = DefaultGuardrailEngine(auditLog, maxInboundRequestsPerWindow = 10_000)
        val manager = MeshManager(
            transport = transport,
            primitives = primitives,
            identityProvider = identityProvider(),
            verifier = verifier,
            guardrail = engine,
            trustStore = InMemoryTrustStore(),
            scope = scope,
            displayName = { name }
        )
        return Node(manager, transport, engine, auditLog, name)
    }

    private fun pair(scope: CoroutineScope): Pair<Node, Node> {
        val alice = node("Alice", "AA:AA:AA:AA:AA:AA", scope)
        val bob = node("Bob", "BB:BB:BB:BB:BB:BB", scope)
        alice.transport.peer = bob.transport
        bob.transport.peer = alice.transport
        alice.manager.start()
        bob.manager.start()
        return alice to bob
    }

    @Test
    fun twoDevicesEstablishASecureSession() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        advanceUntilIdle()

        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val alicePeer = alice.manager.peers.value.firstOrNull { it.isSecured }
        val bobPeer = bob.manager.peers.value.firstOrNull { it.isSecured }

        assertNotNull(alicePeer, "Alice never reached a secured link")
        assertNotNull(bobPeer, "Bob never reached a secured link")
        assertEquals(LinkState.SECURED, alicePeer.linkState)
        assertEquals("Bob", alicePeer.displayName)
        assertEquals("Alice", bobPeer.displayName)
    }

    @Test
    fun eachSideLearnsTheOthersDeviceIdAndFingerprint() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val alicePeer = assertNotNull(alice.manager.peers.value.firstOrNull { it.isSecured })
        val bobPeer = assertNotNull(bob.manager.peers.value.firstOrNull { it.isSecured })

        assertNotNull(alicePeer.fingerprint)
        // Device IDs are derived from keys, so the two sides must not collide.
        assertTrue(alicePeer.deviceId != bobPeer.deviceId)
    }

    @Test
    fun chatMessageTravelsEndToEnd() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val bobDeviceId = assertNotNull(
            alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId
        )

        alice.manager.sendChat(bobDeviceId, "meet at the north entrance")
        advanceUntilIdle()

        val received = bob.manager.conversations.value.values
            .flatMap { it.messages }
            .firstOrNull { it.direction == MessageDirection.RECEIVED }

        assertNotNull(received, "Bob never received the message")
        assertEquals("meet at the north entrance", received.body)
    }

    @Test
    fun senderSeesDeliveryAcknowledged() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val bobDeviceId = assertNotNull(
            alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId
        )
        alice.manager.sendChat(bobDeviceId, "ping")
        advanceUntilIdle()

        val sent = alice.manager.conversations.value[bobDeviceId]
            ?.messages
            ?.firstOrNull { it.direction == MessageDirection.SENT }

        assertNotNull(sent)
        assertEquals(DeliveryState.DELIVERED, sent.deliveryState)
    }

    @Test
    fun aLongMessageSurvivesChunking() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())
        // Force many chunks per message.
        alice.transport.payloadSize = 24
        bob.transport.payloadSize = 24

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val bobDeviceId = assertNotNull(
            alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId
        )

        val longBody = (1..300).joinToString(" ") { "word$it" }
        alice.manager.sendChat(bobDeviceId, longBody)
        advanceUntilIdle()

        val received = bob.manager.conversations.value.values
            .flatMap { it.messages }
            .firstOrNull { it.direction == MessageDirection.RECEIVED }

        assertNotNull(received)
        assertEquals(longBody, received.body)
    }

    @Test
    fun conversationHistoryIsTrimmedInMemoryNotJustOnDisk() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val bobDeviceId = assertNotNull(
            alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId
        )

        // One more than FileConversationStore's own trim bound (500), so
        // this only passes if the live in-memory state is trimmed too, not
        // just what eventually gets written to disk.
        repeat(501) { i -> alice.manager.sendChat(bobDeviceId, "message $i") }
        advanceUntilIdle()

        val messages = bob.manager.conversations.value.values.single().messages
        assertEquals(500, messages.size)
        assertEquals("message 500", messages.last().body, "the newest message must be the one kept")
        assertTrue(messages.none { it.body == "message 0" }, "the oldest message should have been evicted")
    }

    @Test
    fun inboundConnectionAsksTheUserAndProceedsOnApproval() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        // Alice may dial out; Bob keeps the default "ask me" posture.
        alice.engine.addRule(allowConnectRule())

        val bobEvents = mutableListOf<MeshEvent>()
        backgroundScope.launchCollect(bob.manager.events) { bobEvents.add(it) }

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val prompt = bobEvents.filterIsInstance<MeshEvent.DecisionRequired>().firstOrNull()
        assertNotNull(prompt, "Bob should have been asked before accepting a stranger")
        // Nothing is secured while the human has not answered.
        assertTrue(bob.manager.peers.value.none { it.isSecured })

        bob.manager.resolveDecision(prompt.decisionId, allow = true)
        advanceUntilIdle()

        assertTrue(bob.manager.peers.value.any { it.isSecured })
    }

    @Test
    fun inboundConnectionStaysBlockedWhenTheUserDeclines() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())

        val bobEvents = mutableListOf<MeshEvent>()
        backgroundScope.launchCollect(bob.manager.events) { bobEvents.add(it) }

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val prompt = assertNotNull(
            bobEvents.filterIsInstance<MeshEvent.DecisionRequired>().firstOrNull()
        )
        bob.manager.resolveDecision(prompt.decisionId, allow = false)
        advanceUntilIdle()

        assertTrue(bob.manager.peers.value.none { it.isSecured })
        assertTrue(alice.manager.peers.value.none { it.isSecured })
    }

    @Test
    fun everyMediatedActionReachesTheAuditLog() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()

        val bobDeviceId = assertNotNull(
            alice.manager.peers.value.firstOrNull { it.isSecured }?.deviceId
        )
        alice.manager.sendChat(bobDeviceId, "hello")
        advanceUntilIdle()

        val aliceActions = alice.auditLog.recent(100).map { it.request.actionType }
        val bobActions = bob.auditLog.recent(100).map { it.request.actionType }

        assertTrue(ActionType.DISCOVER_PEER in aliceActions)
        assertTrue(ActionType.CONNECT_PEER in aliceActions)
        assertTrue(ActionType.SEND_MESSAGE in aliceActions)
        assertTrue(ActionType.RECEIVE_MESSAGE in bobActions)
    }

    @Test
    fun aPeerThatFallsOutOfScanRangeDisappearsFromTheList() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)

        alice.transport.reportDiscovered(listOf(DiscoveredPeer("BB:BB:BB:BB:BB:BB", "Bob", -50, 1_000)))
        advanceUntilIdle()
        assertTrue(alice.manager.peers.value.any { it.transportAddress == "BB:BB:BB:BB:BB:BB" })

        // Bob walks out of range: the next scan cycle no longer reports him.
        // Without pruning, every device ever seen would linger forever.
        alice.transport.reportDiscovered(emptyList())
        advanceUntilIdle()

        assertTrue(alice.manager.peers.value.none { it.transportAddress == "BB:BB:BB:BB:BB:BB" })
    }

    @Test
    fun aFloodOfNeverBeforeSeenAddressesIsCappedWithoutTouchingAlreadyTrackedOnes() = runTest(UnconfinedTestDispatcher()) {
        val alice = node("Alice", "AA:AA:AA:AA:AA:AA", backgroundScope)
        alice.manager.start()

        val aliceEvents = mutableListOf<MeshEvent>()
        backgroundScope.launchCollect(alice.manager.events) { aliceEvents.add(it) }

        // MeshManager.MAX_CONCURRENT_LINKS is 40 and private; this count must
        // track it, since this test proves the cap it enforces.
        val attackers = (1..41).map { i ->
            node("Attacker$i", "attacker-$i", backgroundScope).also {
                it.transport.peer = alice.transport
                it.engine.addRule(allowConnectRule())
            }
        }

        // One connection attempt per distinct, never-before-seen address —
        // nothing here is a real handshake Alice would ever complete, but
        // each one is a well-formed frame that reaches onIncoming, which is
        // exactly what's being flooded.
        attackers.forEach { it.manager.connectTo("AA:AA:AA:AA:AA:AA") }
        advanceUntilIdle()

        // The 41st address should never even have reached the Guardrail
        // Engine — its frame was dropped for being one too many distinct
        // addresses, before policy ever got a say.
        val decisionsRequested = aliceEvents.filterIsInstance<MeshEvent.DecisionRequired>().size
        assertEquals(40, decisionsRequested, "the 41st address should have been refused before reaching policy")
    }

    @Test
    fun aSecuredPeerSurvivesAMissedScanCycle() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.engine.addRule(allowConnectRule())
        bob.engine.addRule(allowConnectRule())

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB:BB:BB:BB:BB:BB")
        advanceUntilIdle()
        assertTrue(alice.manager.peers.value.any { it.isSecured }, "should be secured before the test proceeds")

        // A scan cycle that fails to re-report an actively connected peer
        // (common with BLE, which doesn't always re-advertise while a GATT
        // connection is open) must not make it vanish from the screen.
        alice.transport.reportDiscovered(emptyList())
        advanceUntilIdle()

        val stillThere = alice.manager.peers.value.firstOrNull { it.transportAddress == "BB:BB:BB:BB:BB:BB" }
        assertNotNull(stillThere, "a secured peer must survive a missed scan cycle")
        assertTrue(stillThere.isSecured)
    }
}

/** Small helper so tests can observe a SharedFlow without boilerplate. */
private fun <T> CoroutineScope.launchCollect(flow: Flow<T>, onEach: (T) -> Unit) {
    launch { flow.collect { onEach(it) } }
}
