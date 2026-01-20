package io.github.commandertvis.huemanager.hue

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Token bucket rate limiter for Philips Hue API requests.
 *
 * Hue bridge rate limits (shared across all connected apps):
 * - Individual lights: ~10 commands per second
 * - Groups: 1 command per second
 *
 * This implementation uses monotonic time to avoid issues with system clock changes.
 */
class RateLimiter(
    private val maxTokens: Int = 10,
    private val refillRate: Duration = 1.seconds
) {
    private var tokens: Double = maxTokens.toDouble()
    private var lastRefillMark: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
    private val mutex = Mutex()

    suspend fun <T> execute(block: suspend () -> T): T {
        awaitToken()
        return block()
    }

    private suspend fun awaitToken() {
        while (true) {
            val waitTime = mutex.withLock {
                refillTokens()

                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return // Token acquired, exit
                }

                // Calculate how long to wait for 1 token
                calculateWaitTime()
            }

            // Wait outside the lock to allow other coroutines to proceed
            delay(waitTime)
        }
    }

    private fun refillTokens() {
        val now = TimeSource.Monotonic.markNow()
        val timePassed = now - lastRefillMark
        val tokensToAdd = (timePassed / refillRate) * maxTokens

        if (tokensToAdd > 0) {
            tokens = minOf(tokens + tokensToAdd, maxTokens.toDouble())
            lastRefillMark = now
        }
    }

    private fun calculateWaitTime(): Duration {
        val tokensNeeded = 1.0 - tokens
        return refillRate * (tokensNeeded / maxTokens)
    }
}
