package os.proximity.shared.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusBoardManagerTest {

    private var clock = 1_000L
    private fun manager() = StatusBoardManager(now = { clock })

    @Test
    fun aPostedStatusIsVisibleImmediately() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "at the north gate", clock, clock + 10_000)

        val current = assertNotNull(manager.currentStatus("peer-1"))
        assertEquals("at the north gate", current.text)
    }

    @Test
    fun anUnknownPeerHasNoStatus() {
        assertNull(manager().currentStatus("nobody"))
    }

    @Test
    fun aNewerStatusReplacesTheOlderOne() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "at the entrance", clock, clock + 10_000)
        manager.onStatusReceived("peer-1", "found it, nevermind", clock, clock + 10_000)

        assertEquals("found it, nevermind", manager.currentStatus("peer-1")?.text)
        assertEquals(1, manager.board.value.size, "only the latest status should be kept, not a history")
    }

    @Test
    fun anExpiredStatusIsNotReturnedByCurrentStatus() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "at the gate", clock, clock + 1_000)

        clock += 5_000

        assertNull(manager.currentStatus("peer-1"))
    }

    @Test
    fun purgeExpiredRemovesStaleEntriesFromTheBoard() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "stale", clock, clock + 1_000)
        manager.onStatusReceived("peer-2", "fresh", clock, clock + 100_000)

        clock += 5_000
        manager.purgeExpired()

        assertEquals(setOf("peer-2"), manager.board.value.keys)
    }

    @Test
    fun purgeExpiredIsANoOpWhenNothingHasExpired() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "here", clock, clock + 100_000)

        manager.purgeExpired()

        assertEquals(1, manager.board.value.size)
    }

    @Test
    fun aStatusRightAtItsExpiryMomentIsTreatedAsExpired() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "here", clock, clock + 1_000)

        clock += 1_000 // exactly at expiresAtEpochMillis

        assertNull(manager.currentStatus("peer-1"), "expiry should be inclusive, matching StatusUpdate.isExpired")
    }

    @Test
    fun aPeersControlledTextIsTruncatedToTheLengthLimit() {
        val manager = manager()
        val huge = "x".repeat(StatusLimits.MAX_TEXT_LENGTH + 500)

        manager.onStatusReceived("peer-1", huge, clock, clock + 10_000)

        val stored = manager.currentStatus("peer-1")!!.text
        assertEquals(StatusLimits.MAX_TEXT_LENGTH, stored.length)
    }

    @Test
    fun differentPeersHaveIndependentStatuses() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "here", clock, clock + 10_000)
        manager.onStatusReceived("peer-2", "there", clock, clock + 10_000)

        assertEquals("here", manager.currentStatus("peer-1")?.text)
        assertEquals("there", manager.currentStatus("peer-2")?.text)
    }

    @Test
    fun boardReflectsAllPostedPeersUntilPurged() {
        val manager = manager()
        manager.onStatusReceived("peer-1", "a", clock, clock + 10_000)
        manager.onStatusReceived("peer-2", "b", clock, clock + 10_000)

        assertTrue(manager.board.value.keys.containsAll(setOf("peer-1", "peer-2")))
    }
}
