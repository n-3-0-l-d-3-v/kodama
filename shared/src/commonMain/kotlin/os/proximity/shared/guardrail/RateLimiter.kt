package os.proximity.shared.guardrail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caps how many events one key (in practice, one peer) may register inside
 * a rolling time window.
 *
 * Exists to close the gap named in docs/THREAT_MODEL.md #6: without it, a
 * peer that simply floods connection attempts or messages costs this
 * device unbounded CPU, battery, and — worse — unbounded AskUser prompts,
 * before any policy rule ever gets a chance to say no.
 *
 * Uses a fixed window rather than a sliding one: simpler to reason about
 * and to test, and precise enough for a defence whose job is "stop a
 * flood," not "meter fairly to the millisecond."
 */
class RateLimiter(
    private val maxEventsPerWindow: Int,
    private val windowMillis: Long,
    private val now: () -> Long
) {
    private class Window(var startedAtEpochMillis: Long, var count: Int)

    private val mutex = Mutex()
    private val windows = mutableMapOf<String, Window>()

    /** Returns true if this event is within the limit, false if it should be refused. */
    suspend fun tryAcquire(key: String): Boolean = mutex.withLock {
        val nowMillis = now()
        val window = windows.getOrPut(key) { Window(nowMillis, 0) }

        if (nowMillis - window.startedAtEpochMillis >= windowMillis) {
            window.startedAtEpochMillis = nowMillis
            window.count = 0
        }

        if (window.count >= maxEventsPerWindow) {
            false
        } else {
            window.count++
            true
        }
    }
}
