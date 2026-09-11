package os.proximity.shared.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.proximity.shared.mesh.StatusBoardDelegate

/**
 * Holds the latest status posted by each connected or recently-connected
 * peer. In-memory only — see [StatusUpdate]'s docs for why persisting this
 * would be actively misleading rather than merely wasteful.
 */
class StatusBoardManager(private val now: () -> Long) : StatusBoardDelegate {

    private val boardState = MutableStateFlow<Map<String, StatusUpdate>>(emptyMap())
    val board: StateFlow<Map<String, StatusUpdate>> = boardState.asStateFlow()

    override fun onStatusReceived(
        peerDeviceId: String,
        text: String,
        postedAtEpochMillis: Long,
        expiresAtEpochMillis: Long
    ) {
        val update = StatusUpdate(
            peerDeviceId = peerDeviceId,
            // A peer controls this string entirely; bounding its length here
            // means nothing downstream has to re-check before rendering it.
            text = text.take(StatusLimits.MAX_TEXT_LENGTH),
            postedAtEpochMillis = postedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis
        )
        boardState.value = boardState.value + (peerDeviceId to update)
    }

    /** The current, unexpired status for one peer, if any. */
    fun currentStatus(peerDeviceId: String): StatusUpdate? =
        boardState.value[peerDeviceId]?.takeUnless { it.isExpired(now()) }

    /** Drops anything past its own expiry. Safe to call opportunistically;
     *  nothing else in this class depends on having been called recently. */
    fun purgeExpired() {
        val nowMillis = now()
        boardState.value = boardState.value.filterValues { !it.isExpired(nowMillis) }
    }
}
