package os.proximity.shared.guardrail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.proximity.shared.util.currentTimeMillis

/**
 * The reference [GuardrailEngine] implementation. Evaluation order, per
 * docs/GUARDRAIL_POLICY.md:
 *
 * 1. Hard-coded safety floor — cannot be disabled by user configuration.
 * 2. User-defined [PolicyRule]s, highest [PolicyRule.priority] first.
 * 3. Category default for the request's [ActionType].
 *
 * Every evaluated request is appended to [auditLog], regardless of outcome.
 */
class DefaultGuardrailEngine(
    private val auditLog: AuditLog
) : GuardrailEngine {

    private val mutex = Mutex()
    private val userRules = mutableListOf<PolicyRule>()

    suspend fun addRule(rule: PolicyRule) = mutex.withLock {
        userRules.add(rule)
    }

    suspend fun removeRule(id: String) = mutex.withLock {
        userRules.removeAll { it.id == id }
    }

    suspend fun rules(): List<PolicyRule> = mutex.withLock { userRules.toList() }

    override suspend fun evaluate(request: GuardrailRequest): GuardrailDecision {
        val decision = SAFETY_FLOOR.firstOrNull { it.matches(request) }?.decide?.invoke(request)
            ?: mutex.withLock { userRules.sortedByDescending { it.priority } }
                .firstOrNull { it.matches(request) }
                ?.decide
                ?.invoke(request)
            ?: categoryDefault(request.actionType)

        auditLog.append(
            AuditLogEntry(
                timestampEpochMillis = currentTimeMillis(),
                request = request,
                decision = decision.toOutcome(),
                reason = decision.reason
            )
        )

        return decision
    }

    companion object {

        /** Cannot be disabled or overridden by user rules. */
        private val SAFETY_FLOOR: List<PolicyRule> = listOf(
            PolicyRule(
                id = "safety-floor-no-code-execution",
                description = "Never allow shell-like or code execution actions.",
                matches = { it.actionType == ActionType.EXECUTE_CODE },
                decide = { GuardrailDecision.Deny("Code execution is never permitted, by design.") }
            )
        )

        private fun categoryDefault(actionType: ActionType): GuardrailDecision = when (actionType) {
            ActionType.DISCOVER_PEER,
            ActionType.ADVERTISE_CAPABILITY ->
                GuardrailDecision.Allow(
                    "Discovering nearby devices and advertising presence is low-risk and required for the mesh to work."
                )

            ActionType.CONNECT_PEER ->
                GuardrailDecision.AskUser(
                    reason = "A nearby device wants to connect. Allow it?",
                    options = listOf("Allow", "Deny")
                )

            ActionType.SEND_MESSAGE,
            ActionType.RECEIVE_MESSAGE,
            // List sync rides inside an already-approved encrypted session.
            // A stricter posture is available as a user policy rather than
            // being forced here, because refusing to sync with someone you
            // just met would defeat the feature.
            ActionType.SYNC_LIST,
            // Sending a file is this device's own choice to make, same as
            // sending a message — the risk worth gating is the inbound
            // side, which RECEIVE_FILE (below, via the default-deny
            // fallback and the FILES_FROM_VERIFIED_ONLY policy) covers.
            ActionType.SEND_FILE,
            // A status ("at the north gate") is low-risk information, both
            // to post and to receive — same posture as messaging, with a
            // stricter user policy available rather than forced here.
            ActionType.SHARE_STATUS ->
                GuardrailDecision.Allow(
                    "Allowed with peers you have already accepted a connection from."
                )

            else ->
                GuardrailDecision.Deny(
                    "No rule allows '${actionType.name}' by default (default-deny). Add a rule in Settings to allow it."
                )
        }

        private fun GuardrailDecision.toOutcome(): AuditLogEntry.DecisionOutcome = when (this) {
            is GuardrailDecision.Allow -> AuditLogEntry.DecisionOutcome.ALLOW
            is GuardrailDecision.Deny -> AuditLogEntry.DecisionOutcome.DENY
            is GuardrailDecision.AskUser -> AuditLogEntry.DecisionOutcome.ASK_USER
        }
    }
}
