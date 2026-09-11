package os.proximity.shared.guardrail

/**
 * A policy the user can turn on or off, described in the words a
 * non-technical person would use.
 *
 * The catalog exists so that policy is not scattered through the codebase
 * as ad-hoc conditionals: every switch a user can flip is one entry here,
 * with the sentence explaining it living next to the rule it produces. If
 * the two ever disagree, the app is lying to its user about its own
 * behaviour.
 */
data class PolicyOption(
    val id: String,
    val title: String,
    val explanation: String,
    val enabledByDefault: Boolean,
    val buildRule: () -> PolicyRule
)

object PolicyCatalog {

    const val AUTO_ACCEPT_VERIFIED = "auto_accept_verified"
    const val FILES_FROM_VERIFIED_ONLY = "files_from_verified_only"
    const val BLOCK_UNVERIFIED_MESSAGES = "block_unverified_messages"
    const val ALLOW_LOCATION_ON_ASK = "allow_location_on_ask"
    const val ALLOW_RELAY = "allow_relay"
    const val LISTS_FROM_VERIFIED_ONLY = "lists_from_verified_only"
    const val STATUS_FROM_VERIFIED_ONLY = "status_from_verified_only"

    val options: List<PolicyOption> = listOf(
        PolicyOption(
            id = AUTO_ACCEPT_VERIFIED,
            title = "Connect to people I've verified without asking",
            explanation = "Once you've checked someone's code in person, their device " +
                "can reconnect without prompting you every time. Everyone else still asks first.",
            enabledByDefault = true,
            buildRule = {
                PolicyRule(
                    id = AUTO_ACCEPT_VERIFIED,
                    description = "Auto-accept connections from verified peers",
                    priority = 50,
                    matches = { request ->
                        request.actionType == ActionType.CONNECT_PEER &&
                            request.peer?.trustState == TrustState.VERIFIED
                    },
                    decide = {
                        GuardrailDecision.Allow("You verified this person previously.")
                    }
                )
            }
        ),

        PolicyOption(
            id = FILES_FROM_VERIFIED_ONLY,
            title = "Only accept files from people I've verified",
            explanation = "Files from strangers are refused automatically. This is the " +
                "single most effective protection against someone nearby sending you something harmful.",
            enabledByDefault = true,
            buildRule = {
                PolicyRule(
                    id = FILES_FROM_VERIFIED_ONLY,
                    description = "Only verified peers may send files",
                    priority = 60,
                    matches = { it.actionType == ActionType.RECEIVE_FILE },
                    decide = { request ->
                        if (request.peer?.trustState == TrustState.VERIFIED) {
                            val name = request.attributes["name"]
                            val label = if (name != null) "\"$name\"" else "a file"
                            GuardrailDecision.AskUser(
                                reason = "${request.peer.deviceId.take(8)} wants to send you $label.",
                                options = listOf("Accept", "Refuse")
                            )
                        } else {
                            GuardrailDecision.Deny(
                                "You only accept files from people you've verified, and you " +
                                    "haven't verified this device yet."
                            )
                        }
                    }
                )
            }
        ),

        PolicyOption(
            id = BLOCK_UNVERIFIED_MESSAGES,
            title = "Ignore messages from people I haven't verified",
            explanation = "Stricter than the default. Nearby devices can still see you exist, " +
                "but anything they write is dropped before you ever see it.",
            enabledByDefault = false,
            buildRule = {
                PolicyRule(
                    id = BLOCK_UNVERIFIED_MESSAGES,
                    description = "Drop messages from unverified peers",
                    priority = 70,
                    matches = { request ->
                        request.actionType == ActionType.RECEIVE_MESSAGE &&
                            request.peer?.trustState != TrustState.VERIFIED
                    },
                    decide = {
                        GuardrailDecision.Deny(
                            "You've chosen to ignore messages from people you haven't verified."
                        )
                    }
                )
            }
        ),

        PolicyOption(
            id = ALLOW_LOCATION_ON_ASK,
            title = "Let me share my location, asking every time",
            explanation = "Off by default: your location is never shared at all. Turned on, " +
                "sharing becomes possible but always requires you to approve it in the moment.",
            enabledByDefault = false,
            buildRule = {
                PolicyRule(
                    id = ALLOW_LOCATION_ON_ASK,
                    description = "Location sharing permitted with per-use confirmation",
                    priority = 40,
                    matches = { it.actionType == ActionType.SHARE_LOCATION },
                    decide = {
                        GuardrailDecision.AskUser(
                            reason = "Share your current location with this person?",
                            options = listOf("Share once", "Don't share")
                        )
                    }
                )
            }
        ),

        PolicyOption(
            id = ALLOW_RELAY,
            title = "Help carry other people's messages",
            explanation = "Lets your phone pass along encrypted messages between devices that " +
                "can't reach each other directly. You can't read what you carry. Uses a little extra battery.",
            enabledByDefault = true,
            buildRule = {
                PolicyRule(
                    id = ALLOW_RELAY,
                    description = "Relay encrypted traffic for other peers",
                    priority = 30,
                    matches = { it.actionType == ActionType.RELAY_MESSAGE },
                    decide = {
                        GuardrailDecision.Allow(
                            "You've chosen to help carry messages for nearby devices."
                        )
                    }
                )
            }
        ),

        PolicyOption(
            id = LISTS_FROM_VERIFIED_ONLY,
            title = "Only share lists with people I have verified",
            explanation = "Off by default, because sharing a shopping list with someone you " +
                "just met is the point. Turn it on if your lists say more about you than you " +
                "would tell a stranger.",
            enabledByDefault = false,
            buildRule = {
                PolicyRule(
                    id = LISTS_FROM_VERIFIED_ONLY,
                    description = "Restrict list sync to verified peers",
                    priority = 65,
                    matches = { request ->
                        request.actionType == ActionType.SYNC_LIST &&
                            request.peer?.trustState != TrustState.VERIFIED
                    },
                    decide = {
                        GuardrailDecision.Deny(
                            "You only share lists with people you have verified, and you have " +
                                "not verified this device yet."
                        )
                    }
                )
            }
        ),

        PolicyOption(
            id = STATUS_FROM_VERIFIED_ONLY,
            title = "Only share status updates with people I have verified",
            explanation = "Off by default — a status board is often most useful with people " +
                "you've just met to coordinate with. Turn it on if your status could reveal " +
                "more than you'd want a stranger to know.",
            enabledByDefault = false,
            buildRule = {
                PolicyRule(
                    id = STATUS_FROM_VERIFIED_ONLY,
                    description = "Restrict status sharing to verified peers",
                    priority = 65,
                    matches = { request ->
                        request.actionType == ActionType.SHARE_STATUS &&
                            request.peer?.trustState != TrustState.VERIFIED
                    },
                    decide = {
                        GuardrailDecision.Deny(
                            "You only share status updates with people you have verified, and " +
                                "you have not verified this device yet."
                        )
                    }
                )
            }
        )
    )

    val defaultEnabledIds: Set<String> =
        options.filter { it.enabledByDefault }.map { it.id }.toSet()

    fun optionById(id: String): PolicyOption? = options.firstOrNull { it.id == id }
}
