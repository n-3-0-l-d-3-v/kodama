package os.proximity.shared.guardrail

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    private var clock = 0L
    private fun limiter(max: Int, windowMillis: Long = 10_000) =
        RateLimiter(max, windowMillis, now = { clock })

    @Test
    fun requestsWithinTheLimitAreAllAcquired() = runTest {
        val limiter = limiter(max = 3)
        assertTrue(limiter.tryAcquire("peer-1"))
        assertTrue(limiter.tryAcquire("peer-1"))
        assertTrue(limiter.tryAcquire("peer-1"))
    }

    @Test
    fun theEventAtTheLimitIsRefused() = runTest {
        val limiter = limiter(max = 3)
        repeat(3) { limiter.tryAcquire("peer-1") }

        assertFalse(limiter.tryAcquire("peer-1"))
    }

    @Test
    fun differentKeysHaveIndependentBudgets() = runTest {
        val limiter = limiter(max = 1)
        assertTrue(limiter.tryAcquire("peer-1"))
        assertTrue(limiter.tryAcquire("peer-2"), "peer-2's budget must not be affected by peer-1's usage")
    }

    @Test
    fun theBudgetResetsOnceTheWindowElapses() = runTest {
        val limiter = limiter(max = 1, windowMillis = 1_000)
        assertTrue(limiter.tryAcquire("peer-1"))
        assertFalse(limiter.tryAcquire("peer-1"))

        clock += 1_000

        assertTrue(limiter.tryAcquire("peer-1"), "a new window should grant a fresh budget")
    }

    @Test
    fun theBudgetDoesNotResetBeforeTheWindowElapses() = runTest {
        val limiter = limiter(max = 1, windowMillis = 1_000)
        assertTrue(limiter.tryAcquire("peer-1"))

        clock += 999

        assertFalse(limiter.tryAcquire("peer-1"))
    }
}
