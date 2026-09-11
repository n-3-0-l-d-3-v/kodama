package os.proximity.shared.status

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import os.proximity.shared.crypto.AndroidCryptoPrimitives
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
import os.proximity.shared.mesh.DiscoveredPeer
import os.proximity.shared.mesh.IncomingMessage
import os.proximity.shared.mesh.MeshManager
import os.proximity.shared.mesh.MeshTransport
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
 * A status update travelling over the real mesh stack — policy, handshake,
 * encryption included — with only the radio replaced.
 * [StatusBoardManagerTest] covers the board's own state machine in
 * isolation; this proves the envelope actually survives the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBoardIntegrationTest {

    private val primitives = AndroidCryptoPrimitives()
    private val verifier = JcaSignatureVerifier()

    private class LoopbackTransport(private val selfAddress: String) : MeshTransport {
        var peer: LoopbackTransport? = null
        private val inbox = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 4096)
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
        override fun maxPayloadSize(peerAddress: String): Int = 60

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
        val board: StatusBoardManager,
        val engine: DefaultGuardrailEngine
    )

    private var clock = 1_000L

    private fun node(name: String, address: String, scope: CoroutineScope): Node {
        val transport = LoopbackTransport(address)
        val engine = DefaultGuardrailEngine(InMemoryAuditLog())
        val board = StatusBoardManager(now = { clock })
        val manager = MeshManager(
            transport = transport,
            primitives = primitives,
            identityProvider = identityProvider(),
            verifier = verifier,
            guardrail = engine,
            trustStore = InMemoryTrustStore(),
            scope = scope,
            displayName = { name },
            statusBoard = board
        )
        return Node(manager, transport, board, engine)
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

    private suspend fun connect(alice: Node) {
        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
    }

    /** Device ID for Alice as seen from Bob's side of an established session. */
    private fun aliceIdOnBob(bob: Node): String =
        assertNotNull(bob.manager.peers.value.first { it.isSecured }.deviceId)

    @Test
    fun aBroadcastStatusArrivesOnTheConnectedPeer() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        connect(alice)
        advanceUntilIdle()

        val delivered = alice.manager.broadcastStatus("at the north gate")
        advanceUntilIdle()

        assertEquals(1, delivered)
        val received = assertNotNull(bob.board.currentStatus(aliceIdOnBob(bob)))
        assertEquals("at the north gate", received.text)
    }

    @Test
    fun aLaterStatusReplacesTheEarlierOneOnTheReceivingSide() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        connect(alice)
        advanceUntilIdle()

        alice.manager.broadcastStatus("at the gate")
        advanceUntilIdle()
        alice.manager.broadcastStatus("found it")
        advanceUntilIdle()

        assertEquals("found it", bob.board.currentStatus(aliceIdOnBob(bob))?.text)
        assertEquals(1, bob.board.board.value.size)
    }

    @Test
    fun statusIsBlockedWhenPolicyDeniesIt() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        bob.engine.addRule(
            PolicyRule(
                id = "no-status",
                description = "Refuse all status updates",
                priority = 200,
                matches = { it.actionType == ActionType.SHARE_STATUS },
                decide = { GuardrailDecision.Deny("Not accepting status updates.") }
            )
        )
        connect(alice)
        advanceUntilIdle()

        alice.manager.broadcastStatus("at the gate")
        advanceUntilIdle()

        assertTrue(bob.board.board.value.isEmpty(), "policy should have stopped the status being recorded")
    }

    @Test
    fun broadcastingWithNoConnectedPeersDeliversToNobody() = runTest(UnconfinedTestDispatcher()) {
        val (alice, _) = pair(backgroundScope)
        val delivered = alice.manager.broadcastStatus("anyone there?")
        assertEquals(0, delivered)
    }

    @Test
    fun theSendersTtlSurvivesTheWireIntact() = runTest(UnconfinedTestDispatcher()) {
        // Expiry math against a controlled clock is [StatusBoardManagerTest]'s
        // job; postedAt/expiresAt here are real wall-clock time (MeshManager
        // stamps them via currentTimeMillis()), so this only checks the TTL
        // the sender asked for is what the receiver ends up holding.
        val (alice, bob) = pair(backgroundScope)
        connect(alice)
        advanceUntilIdle()

        alice.manager.broadcastStatus("here", ttlMillis = 5_000)
        advanceUntilIdle()

        val received = assertNotNull(bob.board.currentStatus(aliceIdOnBob(bob)))
        assertEquals(5_000L, received.expiresAtEpochMillis - received.postedAtEpochMillis)
    }
}
