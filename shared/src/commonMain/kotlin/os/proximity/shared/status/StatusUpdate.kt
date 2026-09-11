package os.proximity.shared.status

import kotlinx.serialization.Serializable

/**
 * The most recent status a peer has told us directly — "at the north gate,"
 * "all clear," "need help." Deliberately just the latest value, not a
 * history: unlike shared lists, there is nothing to merge here. Each
 * device is the sole author of its own status, so the only question ever
 * asked is "what's the newest thing they told us," never "how do two
 * conflicting edits reconcile."
 *
 * This is intentionally **not persisted** across restarts, unlike lists,
 * capabilities, or file drops. "At the north gate" from three days ago
 * before the app was last closed is not useful information; it is
 * actively misleading. A status update lives only as long as the app
 * process and its own [expiresAtEpochMillis], whichever is shorter.
 */
@Serializable
data class StatusUpdate(
    val peerDeviceId: String,
    val text: String,
    val postedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis
}

object StatusLimits {
    /** A status is a short line, not a message — this keeps it that way even
     *  if a peer sends something longer. */
    const val MAX_TEXT_LENGTH = 140

    /** Long enough to be useful during an active coordination session, short
     *  enough that a stale status does not linger and mislead. */
    const val DEFAULT_TTL_MILLIS = 30L * 60 * 1000
}
