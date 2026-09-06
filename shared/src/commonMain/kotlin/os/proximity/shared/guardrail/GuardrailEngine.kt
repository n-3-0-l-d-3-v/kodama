package os.proximity.shared.guardrail

/**
 * The single mediation point for every sensitive action in Kodama.
 *
 * Every inbound frame from the transport/mesh layer and every outbound
 * action initiated by the UI or shared logic must be evaluated here before
 * it is acted on. There is no code path that bypasses this: transport
 * implementations must not expose raw channels to UI or application code
 * directly.
 *
 * See docs/GUARDRAIL_POLICY.md for the policy model this evaluates and
 * docs/THREAT_MODEL.md for the threats it is designed to mitigate.
 */
interface GuardrailEngine {

    /**
     * Evaluate [request] against the current policy set and return a
     * decision. Implementations must default to [GuardrailDecision.Deny]
     * when no rule matches — never [GuardrailDecision.Allow].
     *
     * Every call, regardless of outcome, must result in an entry being
     * appended to the audit log.
     */
    suspend fun evaluate(request: GuardrailRequest): GuardrailDecision
}
