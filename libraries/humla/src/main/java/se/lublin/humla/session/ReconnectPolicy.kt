package se.lublin.humla.session

/**
 * Exponential backoff for automatic reconnects (spec A3): 2 s, 4 s, 8 s, 16 s, then 30 s,
 * plus up to [maxJitterFraction] of the delay as jitter, for at most [maxAttempts] attempts.
 * The caller resets the attempt counter when connectivity changes or a session succeeds.
 */
class ReconnectPolicy(
    val baseDelayMillis: Long = 2_000L,
    val maxDelayMillis: Long = 30_000L,
    val maxAttempts: Int = 10,
    val maxJitterFraction: Double = 0.25,
) {
    /**
     * @param attempt 1-based attempt number.
     * @param jitterUnit a value in [0, 1) that scales the jitter (pass a random number).
     * @return the delay in milliseconds, or null when [attempt] exceeds [maxAttempts].
     */
    fun delayFor(attempt: Int, jitterUnit: Double): Long? {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        require(jitterUnit >= 0.0 && jitterUnit < 1.0) { "jitterUnit must be in [0, 1), was $jitterUnit" }
        if (attempt > maxAttempts) return null
        val shift = (attempt - 1).coerceAtMost(30)
        val exponential = (baseDelayMillis shl shift).coerceAtMost(maxDelayMillis)
        val jitter = (exponential * maxJitterFraction * jitterUnit).toLong()
        return exponential + jitter
    }
}
