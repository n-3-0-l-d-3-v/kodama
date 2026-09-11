package os.proximity.shared.guardrail

import kotlinx.serialization.Serializable

/**
 * Whether the action originates from a peer (something being asked of / sent
 * to this device) or from this device itself (something about to be sent or
 * done).
 */
@Serializable
enum class RequestDirection {
    INBOUND,
    OUTBOUND
}

/**
 * The catalog of action types the Guardrail Engine can mediate. Every
 * sensitive operation in the system must be expressible as one of these —
 * code that cannot express its intent as an ActionType has no path to the
 * transport or platform APIs.
 */
@Serializable
enum class ActionType {
    DISCOVER_PEER,
    CONNECT_PEER,
    RECEIVE_FILE,
    SEND_FILE,
    RECEIVE_MESSAGE,
    SEND_MESSAGE,
    READ_CONTACTS,
    READ_CALENDAR,
    SHARE_LOCATION,
    ADVERTISE_CAPABILITY,
    REQUEST_CAPABILITY,
    RELAY_MESSAGE,
    SYNC_LIST,
    SHARE_STATUS,
    LEAVE_MESH,
    EXECUTE_CODE
}

/**
 * Trust state of the peer associated with a request, as known to this
 * device. Determined by the identity layer, not by the peer's own claims.
 */
@Serializable
enum class TrustState {
    UNVERIFIED,
    VERIFIED
}

/**
 * The peer involved in a request, if any. Purely local, informational data —
 * never trust fields on this class that could be supplied by the peer
 * itself without independent verification.
 */
@Serializable
data class PeerContext(
    val deviceId: String,
    val trustState: TrustState,
    val grantedCapabilities: Set<String> = emptySet()
)

/**
 * A single action submitted to the Guardrail Engine for evaluation. Every
 * inbound frame from the mesh and every outbound action initiated by the UI
 * or shared logic must be wrapped as a GuardrailRequest before it is acted
 * on.
 */
@Serializable
data class GuardrailRequest(
    val direction: RequestDirection,
    val actionType: ActionType,
    val peer: PeerContext? = null,
    val attributes: Map<String, String> = emptyMap()
)
