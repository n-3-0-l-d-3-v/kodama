package os.proximity.shared.capability

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
import os.proximity.shared.storage.InMemoryFileStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Capability advertisements travelling over the real mesh stack — policy,
 * handshake, encryption included — with only the radio replaced.
 *
 * [CapabilityRegistryTest] covers filtering, expiry, and catalog validation
 * against the registry directly; this proves the advertisement actually
 * survives the wire and is exchanged automatically once a session forms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityIntegrationTest {

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
        override fun maxPayloadSize(peerAddress: String): Int = 80

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
        val registry: CapabilityRegistry,
        val engine: DefaultGuardrailEngine
    )

    private var clock = 1_000L

    private fun node(name: String, address: String, scope: CoroutineScope): Node {
        val transport = LoopbackTransport(address)
        val engine = DefaultGuardrailEngine(InMemoryAuditLog(), maxInboundRequestsPerWindow = 10_000)
        val registry = CapabilityRegistry(files = InMemoryFileStore(), now = { clock })
        val manager = MeshManager(
            transport = transport,
            primitives = primitives,
            identityProvider = identityProvider(),
            verifier = verifier,
            guardrail = engine,
            trustStore = InMemoryTrustStore(),
            scope = scope,
            displayName = { name },
            capabilities = registry
        )
        return Node(manager, transport, registry, engine)
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

    private fun aliceIdOnBob(bob: Node): String =
        assertNotNull(bob.manager.peers.value.first { it.isSecured }.deviceId)

    @Test
    fun capabilitiesEnabledBeforeConnectingAreAdvertisedOnHandshake() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.registry.setEnabled(CapabilityCatalog.FILE_DROP, true)

        connect(alice)
        advanceUntilIdle()

        val aliceId = aliceIdOnBob(bob)
        assertTrue(bob.registry.peerOffers(aliceId, CapabilityCatalog.FILE_DROP))
        // CHAT is enabled by default, so it should ride along too.
        assertTrue(bob.registry.peerOffers(aliceId, CapabilityCatalog.CHAT))
    }

    @Test
    fun aCapabilityNeverEnabledIsNeverOffered() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        // RELAY is off by default and never turned on here.

        connect(alice)
        advanceUntilIdle()

        assertFalse(bob.registry.peerOffers(aliceIdOnBob(bob), CapabilityCatalog.RELAY))
    }

    @Test
    fun anAdvertisedCapabilityExpiresAfterItsLifetime() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        alice.registry.setEnabled(CapabilityCatalog.FILE_DROP, true)

        connect(alice)
        advanceUntilIdle()

        val aliceId = aliceIdOnBob(bob)
        assertTrue(bob.registry.peerOffers(aliceId, CapabilityCatalog.FILE_DROP))

        clock += CapabilityRegistry.DEFAULT_LIFETIME_MILLIS + 1

        assertFalse(bob.registry.peerOffers(aliceId, CapabilityCatalog.FILE_DROP))
    }

    @Test
    fun capabilityAdvertisementIsBlockedWhenPolicyDeniesIt() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        bob.engine.addRule(
            PolicyRule(
                id = "no-capabilities",
                description = "Refuse all capability advertisements",
                priority = 200,
                matches = { it.actionType == ActionType.ADVERTISE_CAPABILITY },
                decide = { GuardrailDecision.Deny("Not accepting capability claims.") }
            )
        )

        connect(alice)
        advanceUntilIdle()

        // CHAT is enabled by default, but policy should have stopped it
        // from ever being recorded on Bob's side.
        assertFalse(bob.registry.peerOffers(aliceIdOnBob(bob), CapabilityCatalog.CHAT))
    }

    @Test
    fun disconnectingForgetsThePeersCapabilities() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        connect(alice)
        advanceUntilIdle()

        val aliceId = aliceIdOnBob(bob)
        assertTrue(bob.registry.peerOffers(aliceId, CapabilityCatalog.CHAT))

        bob.manager.disconnect("AA")
        advanceUntilIdle()

        assertTrue(bob.registry.peerCapabilities.value[aliceId].isNullOrEmpty())
    }
}
