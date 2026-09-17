package os.proximity.shared.guardrail

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultGuardrailEngineTest {

    private fun engine(): Pair<DefaultGuardrailEngine, InMemoryAuditLog> {
        val log = InMemoryAuditLog()
        return DefaultGuardrailEngine(log) to log
    }

    private fun request(
        action: ActionType,
        direction: RequestDirection = RequestDirection.OUTBOUND,
        peer: PeerContext? = null
    ) = GuardrailRequest(direction, action, peer)

    @Test
    fun unknownSensitiveActionDefaultsToDeny() = runTest {
        val (engine, _) = engine()

        // Default-deny is the whole posture: an action nobody wrote a rule
        // for must be blocked, not allowed.
        val decision = engine.evaluate(request(ActionType.READ_CONTACTS))

        assertIs<GuardrailDecision.Deny>(decision)
    }

    @Test
    fun safetyFloorCannotBeOverriddenByAUserRule() = runTest {
        val (engine, _) = engine()

        engine.addRule(
            PolicyRule(
                id = "user-allows-everything",
                description = "Allow absolutely anything",
                priority = Int.MAX_VALUE,
                matches = { true },
                decide = { GuardrailDecision.Allow("user said so") }
            )
        )

        val decision = engine.evaluate(request(ActionType.EXECUTE_CODE))

        assertIs<GuardrailDecision.Deny>(decision)
        assertTrue(decision.reason.isNotBlank())
    }

    @Test
    fun discoveryIsAllowedByDefault() = runTest {
        val (engine, _) = engine()
        assertIs<GuardrailDecision.Allow>(engine.evaluate(request(ActionType.DISCOVER_PEER)))
    }

    @Test
    fun connectingToAPeerAsksTheUserByDefault() = runTest {
        val (engine, _) = engine()
        assertIs<GuardrailDecision.AskUser>(engine.evaluate(request(ActionType.CONNECT_PEER)))
    }

    @Test
    fun higherPriorityRuleWins() = runTest {
        val (engine, _) = engine()

        engine.addRule(
            PolicyRule(
                id = "low",
                description = "low priority allow",
                priority = 1,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.Allow("low") }
            )
        )
        engine.addRule(
            PolicyRule(
                id = "high",
                description = "high priority deny",
                priority = 10,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { GuardrailDecision.Deny("high") }
            )
        )

        val decision = engine.evaluate(request(ActionType.RECEIVE_FILE))

        assertIs<GuardrailDecision.Deny>(decision)
        assertEquals("high", decision.reason)
    }

    @Test
    fun ruleCanDiscriminateOnPeerTrustState() = runTest {
        val (engine, _) = engine()

        engine.addRule(
            PolicyRule(
                id = "files-from-verified-only",
                description = "Only accept files from verified people",
                priority = 5,
                matches = { it.actionType == ActionType.RECEIVE_FILE },
                decide = { req ->
                    if (req.peer?.trustState == TrustState.VERIFIED) {
                        GuardrailDecision.Allow("Sender is verified.")
                    } else {
                        GuardrailDecision.Deny("Only verified people can send you files.")
                    }
                }
            )
        )

        val fromVerified = engine.evaluate(
            request(ActionType.RECEIVE_FILE, peer = PeerContext("a", TrustState.VERIFIED))
        )
        val fromStranger = engine.evaluate(
            request(ActionType.RECEIVE_FILE, peer = PeerContext("b", TrustState.UNVERIFIED))
        )

        assertIs<GuardrailDecision.Allow>(fromVerified)
        assertIs<GuardrailDecision.Deny>(fromStranger)
    }

    @Test
    fun removingARuleRestoresTheDefault() = runTest {
        val (engine, _) = engine()
        engine.addRule(
            PolicyRule(
                id = "temp",
                description = "temporarily allow contacts",
                matches = { it.actionType == ActionType.READ_CONTACTS },
                decide = { GuardrailDecision.Allow("temp") }
            )
        )
        assertIs<GuardrailDecision.Allow>(engine.evaluate(request(ActionType.READ_CONTACTS)))

        engine.removeRule("temp")

        assertIs<GuardrailDecision.Deny>(engine.evaluate(request(ActionType.READ_CONTACTS)))
    }

    @Test
    fun everyDecisionIsAudited() = runTest {
        val (engine, log) = engine()

        engine.evaluate(request(ActionType.DISCOVER_PEER))
        engine.evaluate(request(ActionType.EXECUTE_CODE))
        engine.evaluate(request(ActionType.CONNECT_PEER))

        val entries = log.recent()

        // Auditability is a core principle: allows are logged too, not just
        // blocks, or the log could not answer "what did this app do?".
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.decision == AuditLogEntry.DecisionOutcome.ALLOW })
        assertTrue(entries.any { it.decision == AuditLogEntry.DecisionOutcome.DENY })
        assertTrue(entries.any { it.decision == AuditLogEntry.DecisionOutcome.ASK_USER })
        assertTrue(entries.all { it.reason.isNotBlank() })
    }

    @Test
    fun auditLogIsNewestFirstAndBounded() = runTest {
        val log = InMemoryAuditLog(maxEntries = 5)
        val engine = DefaultGuardrailEngine(log)

        repeat(10) { engine.evaluate(request(ActionType.DISCOVER_PEER)) }

        val entries = log.recent(limit = 100)
        assertEquals(5, entries.size)
        // A peer that spams requests must not be able to push the log into
        // unbounded memory (docs/THREAT_MODEL.md #6).
        assertTrue(entries.first().timestampEpochMillis >= entries.last().timestampEpochMillis)
    }

    @Test
    fun aPeerFloodingInboundRequestsIsThrottled() = runTest {
        var clock = 0L
        val log = InMemoryAuditLog()
        val engine = DefaultGuardrailEngine(
            log,
            now = { clock },
            maxInboundRequestsPerWindow = 3
        )
        val flood = request(
            ActionType.RECEIVE_MESSAGE,
            direction = RequestDirection.INBOUND,
            peer = PeerContext("flooder", TrustState.UNVERIFIED)
        )

        repeat(3) { assertIs<GuardrailDecision.Allow>(engine.evaluate(flood)) }
        val throttled = engine.evaluate(flood)

        assertIs<GuardrailDecision.Deny>(throttled)
        assertTrue(throttled.reason.isNotBlank())
    }

    @Test
    fun theRateLimitBudgetResetsAfterTheWindowElapses() = runTest {
        var clock = 0L
        val engine = DefaultGuardrailEngine(
            InMemoryAuditLog(),
            now = { clock },
            maxInboundRequestsPerWindow = 1,
            rateLimitWindowMillis = 1_000
        )
        val flood = request(
            ActionType.RECEIVE_MESSAGE,
            direction = RequestDirection.INBOUND,
            peer = PeerContext("flooder", TrustState.UNVERIFIED)
        )

        assertIs<GuardrailDecision.Allow>(engine.evaluate(flood))
        assertIs<GuardrailDecision.Deny>(engine.evaluate(flood))

        clock += 1_000

        assertIs<GuardrailDecision.Allow>(engine.evaluate(flood))
    }

    @Test
    fun theRateLimitIsPerPeerNotGlobal() = runTest {
        val engine = DefaultGuardrailEngine(InMemoryAuditLog(), maxInboundRequestsPerWindow = 1)
        val fromA = request(
            ActionType.RECEIVE_MESSAGE,
            direction = RequestDirection.INBOUND,
            peer = PeerContext("peer-a", TrustState.UNVERIFIED)
        )
        val fromB = fromA.copy(peer = PeerContext("peer-b", TrustState.UNVERIFIED))

        assertIs<GuardrailDecision.Allow>(engine.evaluate(fromA))
        assertIs<GuardrailDecision.Deny>(engine.evaluate(fromA))
        assertIs<GuardrailDecision.Allow>(engine.evaluate(fromB), "peer-b's budget must be independent of peer-a's")
    }

    @Test
    fun outboundRequestsAreNeverRateLimited() = runTest {
        val engine = DefaultGuardrailEngine(InMemoryAuditLog(), maxInboundRequestsPerWindow = 1)
        val ourOwnSend = request(
            ActionType.SEND_MESSAGE,
            direction = RequestDirection.OUTBOUND,
            peer = PeerContext("peer-a", TrustState.UNVERIFIED)
        )

        repeat(5) { assertIs<GuardrailDecision.Allow>(engine.evaluate(ourOwnSend)) }
    }

    @Test
    fun deniedReasonsAreHumanReadable() = runTest {
        val (engine, _) = engine()
        val decision = engine.evaluate(request(ActionType.SHARE_LOCATION))

        assertIs<GuardrailDecision.Deny>(decision)
        // "User Understanding" principle: reasons are shown verbatim to a
        // non-technical person, so they must not be bare enum names.
        assertTrue(decision.reason.length > 20, "reason was: ${decision.reason}")
    }
}
