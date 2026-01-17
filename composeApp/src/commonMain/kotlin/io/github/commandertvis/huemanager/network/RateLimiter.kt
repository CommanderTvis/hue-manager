package io.github.commandertvis.huemanager.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Simple rate limiter with minimum delay between calls.
 * Used for external API calls like discovery.meethue.com.
 */
class MinimumDelayRateLimiter(
    private val minimumDelay: Duration = 5.seconds
) {
    private var lastCallMark: TimeSource.Monotonic.ValueTimeMark? = null
    private val mutex = Mutex()

    /**
     * Returns the remaining wait time if rate limited, or Duration.ZERO if ready.
     */
    suspend fun getRemainingWaitTime(): Duration {
        return mutex.withLock {
            val mark = lastCallMark ?: return@withLock Duration.ZERO
            val elapsed = mark.elapsedNow()
            if (elapsed < minimumDelay) {
                minimumDelay - elapsed
            } else {
                Duration.ZERO
            }
        }
    }

    /**
     * Records that a call was made. Call this after a successful or attempted request.
     */
    suspend fun recordCall() {
        mutex.withLock {
            lastCallMark = TimeSource.Monotonic.markNow()
        }
    }

    /**
     * Checks if a call can be made now without waiting.
     */
    suspend fun canCallNow(): Boolean = getRemainingWaitTime() == Duration.ZERO
}
